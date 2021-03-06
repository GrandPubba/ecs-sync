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
package com.emc.ecs.sync.filter;

import com.emc.ecs.sync.SyncPlugin;
import com.emc.ecs.sync.model.object.SyncObject;
import com.emc.ecs.sync.source.SyncSource;
import com.emc.ecs.sync.target.SyncTarget;
import com.emc.ecs.sync.util.ConfigurationException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Iterator;

/**
 * Logs the Input IDs to Output IDs
 *
 * @author cwikj
 */
public class IdLoggingFilter extends SyncFilter {
    private static final Logger log = LoggerFactory.getLogger(IdLoggingFilter.class);

    public static final String ACTIVATION_NAME = "id-logging";

    public static final String IDLOG_OPTION = "id-log-file";
    public static final String IDLOG_DESC = "The path to the file to log IDs to";
    public static final String IDLOG_ARG_NAME = "filename";

    private String filename;
    private PrintWriter out;

    @Override
    public String getActivationName() {
        return ACTIVATION_NAME;
    }

    @Override
    public Options getCustomOptions() {
        Options opts = new Options();
        opts.addOption(Option.builder().longOpt(IDLOG_OPTION).desc(IDLOG_DESC)
                .hasArg().argName(IDLOG_ARG_NAME).build());
        return opts;
    }

    @Override
    public void parseCustomOptions(CommandLine line) {
        if (line.hasOption(IDLOG_OPTION))
            setFilename(line.getOptionValue(IDLOG_OPTION));
    }

    @Override
    public void configure(SyncSource source, Iterator<SyncFilter> filters, SyncTarget target) {
        try {
            out = new PrintWriter(new BufferedWriter(new FileWriter(new File(filename))));
        } catch (FileNotFoundException e) {
            throw new ConfigurationException("log file not found", e);
        } catch (IOException e) {
            throw new RuntimeException("could not write to log file", e);
        }
    }

    @Override
    public synchronized void filter(SyncObject obj) {
        try {
            getNext().filter(obj);
            out.println(obj.getSourceIdentifier() + ", " + obj.getTargetIdentifier());
        } catch (RuntimeException e) {
            // Log the error
            out.println(obj.getSourceIdentifier() + ", FAILED: " + e.getMessage());
            throw e;
        }
    }

    @Override
    public SyncObject reverseFilter(SyncObject obj) {
        return getNext().reverseFilter(obj);
    }

    @Override
    public void cleanup() {
        super.cleanup();
        if (out != null) {
            try {
                out.close();
            } catch (Throwable t) {
                log.warn("could not close file", t);
            }
            out = null;
        }
    }

    @Override
    public String getName() {
        return "ID Logging Filter";
    }

    /**
     * @see SyncPlugin#getDocumentation()
     */
    @Override
    public String getDocumentation() {
        return "Logs the input and output Object IDs to a file.  These IDs " +
                "are specific to the source and target plugins.";
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }
}
