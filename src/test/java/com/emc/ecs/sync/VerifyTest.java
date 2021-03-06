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
package com.emc.ecs.sync;

import com.emc.ecs.sync.filter.SyncFilter;
import com.emc.ecs.sync.model.object.SyncObject;
import com.emc.ecs.sync.test.ByteAlteringFilter;
import com.emc.ecs.sync.test.TestObjectSource;
import com.emc.ecs.sync.test.TestObjectTarget;
import com.emc.ecs.sync.test.TestSyncObject;
import org.junit.Assert;
import org.junit.Test;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class VerifyTest {
    @Test
    public void testSuccess() throws Exception {
        TestObjectSource testSource = new TestObjectSource(1000, 10240, null);
        TestObjectTarget testTarget = new TestObjectTarget();

        // send test data to test system
        EcsSync sync = new EcsSync();
        sync.setSource(testSource);
        sync.setTarget(testTarget);
        sync.setSyncThreadCount(16);
        sync.setVerify(true);
        sync.run();

        Assert.assertEquals(0, sync.getObjectsFailed());

        verifyObjects(testSource.getObjects(), testTarget.getRootObjects());
    }

    @Test
    public void testByteAlteringFilter() throws Exception {
        Random random = new Random();
        byte[] buffer = new byte[1024];
        int errorCount = 0;
        ByteAlteringFilter filter = new ByteAlteringFilter();
        TestObjectTarget target = new TestObjectTarget();
        filter.setNext(target);
        for (int i = 0; i < 10000; i++) {
            random.nextBytes(buffer);
            TestSyncObject object = new TestSyncObject(target, "foo" + i, "foo" + i, buffer, null);
            target.ingest(Collections.singletonList(object));
            String originalMd5 = object.getMd5Hex(true);
            SyncObject newObject = filter.reverseFilter(object);
            String newMd5 = newObject.getMd5Hex(true);
            if (!originalMd5.equals(newMd5)) {
                errorCount++;
            }
        }

        Assert.assertEquals(errorCount, filter.getModifiedObjects());
    }

    protected byte[] md5(byte[] buffer) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        return digest.digest(buffer);
    }

    @Test
    public void testFailures() throws Exception {
        TestObjectSource testSource = new TestObjectSource(1000, 10240, null);
        ByteAlteringFilter testFilter = new ByteAlteringFilter();
        TestObjectTarget testTarget = new TestObjectTarget();

        // send test data to test system
        EcsSync sync = new EcsSync();
        sync.setSource(testSource);
        sync.setFilters(Collections.singletonList((SyncFilter) testFilter));
        sync.setTarget(testTarget);
        sync.setSyncThreadCount(16);
        sync.setVerify(true);
        sync.setRetryAttempts(0); // retry would circumvent our test
        sync.run();

        Assert.assertEquals(testFilter.getModifiedObjects(), sync.getObjectsFailed());
    }

    @Test
    public void testVerifyOnly() throws Exception {
        TestObjectSource testSource = new TestObjectSource(1000, 10240, null);
        testSource.configure(null, null, null); // generates objects
        ByteAlteringFilter testFilter = new ByteAlteringFilter();
        TestObjectTarget testTarget = new TestObjectTarget();

        // must pre-ingest objects to the target so we have something to verify against
        testTarget.ingest(testSource.getObjects());

        // send test data to test system
        EcsSync sync = new EcsSync();
        sync.setSource(testSource);
        sync.setFilters(Collections.singletonList((SyncFilter) testFilter));
        sync.setTarget(testTarget);
        sync.setSyncThreadCount(16);
        sync.setVerifyOnly(true);
        sync.run();

        Assert.assertEquals(testFilter.getModifiedObjects(), sync.getObjectsFailed());
    }

    public static void verifyObjects(List<TestSyncObject> sourceObjects, List<TestSyncObject> targetObjects) {
        for (TestSyncObject sourceObject : sourceObjects) {
            String currentPath = sourceObject.getRelativePath();
            Assert.assertTrue(currentPath + " - missing from target", targetObjects.contains(sourceObject));
            for (TestSyncObject targetObject : targetObjects) {
                if (sourceObject.getRelativePath().equals(targetObject.getRelativePath())) {
                    Assert.assertEquals("relative paths not equal", sourceObject.getRelativePath(), targetObject.getRelativePath());
                    if (sourceObject.isDirectory()) {
                        Assert.assertTrue(currentPath + " - source is directory but target is not", targetObject.isDirectory());
                        verifyObjects(sourceObject.getChildren(), targetObject.getChildren());
                    } else {
                        Assert.assertFalse(currentPath + " - source is data object but target is not", targetObject.isDirectory());
                        Assert.assertEquals(currentPath + " - content-type different", sourceObject.getMetadata().getContentType(),
                                targetObject.getMetadata().getContentType());
                        Assert.assertEquals(currentPath + " - data size different", sourceObject.getMetadata().getContentLength(),
                                targetObject.getMetadata().getContentLength());
                        Assert.assertArrayEquals(currentPath + " - data not equal", sourceObject.getData(), targetObject.getData());
                    }
                }
            }
        }
    }

}
