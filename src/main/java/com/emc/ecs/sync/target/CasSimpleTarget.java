/*
 * Copyright 2013-2015 EMC Corporation. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0.txt
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.emc.ecs.sync.target;

import com.emc.ecs.sync.EcsSync;
import com.emc.ecs.sync.filter.SyncFilter;
import com.emc.ecs.sync.model.SyncMetadata;
import com.emc.ecs.sync.model.object.ClipSyncObject;
import com.emc.ecs.sync.model.object.SyncObject;
import com.emc.ecs.sync.source.CasSource;
import com.emc.ecs.sync.source.FilesystemSource;
import com.emc.ecs.sync.source.SyncSource;
import com.emc.ecs.sync.util.ClipTag;
import com.emc.ecs.sync.util.ConfigurationException;
import com.emc.ecs.sync.util.TimingUtil;
import com.emc.object.util.ProgressInputStream;
import com.emc.object.util.ProgressListener;
import com.filepool.fplibrary.*;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CasSimpleTarget extends SyncTarget {
    private static final Logger log = LoggerFactory.getLogger(CasSimpleTarget.class);

    protected static final String URI_PREFIX = "cas-simple:";
    protected static final String URI_PATTERN = "^" + URI_PREFIX + "hpp://([^/]*?)(:[0-9]+)?(,([^/]*?)(:[0-9]+)?)*\\?.*$";
    protected static final String OPERATION_TOTAL = "TotalTime";

    protected static final String OPERATION_STREAM_BLOB = "CasStreamBlob";
    public static final String OPERATION_CREATE_CLIP = "CasCreateClip";
    protected static final String OPERATION_WRITE_CLIP = "CasWriteClip";

    protected static final String APPLICATION_NAME = CasSimpleTarget.class.getName();
    protected static final String APPLICATION_VERSION = EcsSync.class.getPackage().getImplementationVersion();

    protected String connectionString;
    protected FPPool pool;

    @Override
    public boolean canHandleTarget(String targetUri) { return targetUri.startsWith(URI_PREFIX); }

    @Override
    public Options getCustomOptions() {
        return new Options();
    }

    @Override
    protected void parseCustomOptions(CommandLine line) {
        Pattern p = Pattern.compile(this.URI_PATTERN);
        Matcher m = p.matcher(targetUri);
        if (!m.matches())
            throw new ConfigurationException(String.format("%s does not match %s", targetUri, p));

        connectionString = targetUri.replaceFirst("^" + this.URI_PREFIX, "");
    }

    @Override
    public void configure(SyncSource source, Iterator<SyncFilter> filters, SyncTarget target) {
        if (source instanceof CasSource)
            throw new ConfigurationException("CasSimpleTarget is currently not compatible with CasSource");

        Assert.hasText(connectionString);

        try {
            FPPool.RegisterApplication(APPLICATION_NAME, APPLICATION_VERSION);

            // Check connection
            pool = new FPPool(connectionString);
            FPPool.PoolInfo info = pool.getPoolInfo();
            log.info("Connected to target: {} ({}) using CAS v.{}",
                    info.getClusterName(), info.getClusterID(), info.getVersion());

            // verify we have appropriate privileges
            if (pool.getCapability(FPLibraryConstants.FP_WRITE, FPLibraryConstants.FP_ALLOWED).equals("False"))
                throw new IllegalArgumentException("WRITE is not supported for this pool connection");
        } catch (FPLibraryException e) {
            throw new RuntimeException("error creating pool: " + this.summarizeError(e), e);
        }

        if (monitorPerformance) {
            readPerformanceCounter = defaultPerformanceWindow();
            writePerformanceCounter = defaultPerformanceWindow();
        }
    }

    @Override
    public void filter(SyncObject obj){
        timeOperationStart(this.OPERATION_TOTAL);

        if (obj.isDirectory()) {
            log.debug("Target is root folder; skipping");
            return;
        }

        FPClip clip = null;
        FPTag topTag = null;
        FPTag tag = null;
        int targetTagNum = 0;

        try (final InputStream data = obj.getInputStream()) {
            // create the new clip
            clip = TimingUtil.time(this, this.OPERATION_CREATE_CLIP, new Callable<FPClip>() {
                @Override
                public FPClip call() throws Exception {
                    return new FPClip(pool);
                }
            });

            topTag = clip.getTopTag();

            SyncMetadata smd = obj.getMetadata();

            // collect system metadata
            tag = new FPTag(topTag, "x-emc-data");
            tag.setAttribute("contenttype-emc-meta", smd.getContentType());
            tag.setAttribute("contentlength-emc-meta", smd.getContentLength());

            // collect user metadata
            Map<String, SyncMetadata.UserMetadata> umd = smd.getUserMetadata();
            for (SyncMetadata.UserMetadata meta : umd.values()) {
                tag.setAttribute(meta.getKey() + "-emc-meta", meta.getValue());
            }

            final FPTag fTag = tag;

            TimingUtil.time(this, this.OPERATION_STREAM_BLOB, (Callable) new Callable<Void>(){
                @Override
                public Void call() throws Exception {
                    ProgressListener targetProgress = isMonitorPerformance() ? new CasSimpleTargetProgress() : null;
                    InputStream stream = data;
                    if (targetProgress != null) stream = new ProgressInputStream(stream, targetProgress);
                    fTag.BlobWrite(stream);
                    return null;
                }
            });

            final FPClip fClip = clip;
            String destClipId = TimingUtil.time(this, this.OPERATION_WRITE_CLIP, new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return fClip.Write();
                }
            });

            obj.setTargetIdentifier(destClipId);

            log.debug("Wrote source {} to dest {}", obj.getSourceIdentifier(), obj.getTargetIdentifier());

        } catch (Throwable t) {
            timeOperationFailed(this.OPERATION_TOTAL);
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            if (t instanceof FPLibraryException)
                throw new RuntimeException("Failed to store object: " + this.summarizeError((FPLibraryException) t), t);
            throw new RuntimeException("Failed to store object: " + t.getMessage(), t);
        } finally {
            // close current tag ref
            try {
                if (tag != null) tag.Close();
            } catch (Throwable t) {
                log.warn("could not close tag " + obj.getRawSourceIdentifier() + "." + targetTagNum, t);
            }
            // close top tag ref
            try {
                if (topTag != null) topTag.Close();
            } catch (Throwable t) {
                log.warn("could not close top tag " + obj.getRawSourceIdentifier() + "." + targetTagNum, t);
            }
            // close clip
            try {
                if (clip != null) clip.Close();
            } catch (Throwable t) {
                log.warn("could not close clip " + obj.getRawSourceIdentifier(), t);
            }
        }
    }

    private class CasSimpleTargetProgress implements ProgressListener {

        @Override
        public void progress(long completed, long total) {

        }

        @Override
        public void transferred(long size) {
            if(writePerformanceCounter != null) {
                writePerformanceCounter.increment(size);
            }
        }
    }

    @Override
    public SyncObject reverseFilter(final SyncObject obj) {
        if (!(obj instanceof ClipSyncObject)) throw new UnsupportedOperationException("sync object was not a CAS clip");

        String clipId = ((ClipSyncObject) obj).getRawSourceIdentifier();
        obj.setTargetIdentifier(clipId);
        return new ClipSyncObject(this, pool, clipId, this.generateRelativePath(clipId));
    }

    private static String generateRelativePath(String clipId) {
        return clipId + ".cdf";
    }

    private static String summarizeError(FPLibraryException e) {
        return String.format("CAS Simple Error %s/%s: %s", e.getErrorCode(), e.getErrorString(), e.getMessage());
    }

    @Override
    public String getName() {
        return "CAS Simple Target";
    }

    @Override
    public String getDocumentation() {
        return "The CAS Simple target plugin is triggered by the target pattern:\n" +
                "cas-simple:hpp://host[:port][,host[:port]...]?name=<name>,secret=<secret>\n" +
                "or cas-simple:hpp://host[:port][,host[:port]...]?<pea_file>\n" +
                "Note that <name> should be of the format <subtenant_id>:<uid> " +
                "when connecting to an Atmos system. " +
                "This is passed to the CAS API as the connection string " +
                "(you can use primary=, secondary=, etc. in the server hints).\n" +
                "When used with CasSource, clips are transferred using their " +
                "raw CDFs to facilitate transparent data migration.\n" +
                "NOTE: verification of CAS objects (using --verify or --verify-only) " +
                "will only verify the CDF and not blob data!";
    }
}
