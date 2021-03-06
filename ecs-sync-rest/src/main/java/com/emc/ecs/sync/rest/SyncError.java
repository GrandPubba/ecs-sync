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
package com.emc.ecs.sync.rest;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

@XmlType(propOrder = {"sourceId", "targetId", "directory", "size", "transferStart", "retryCount", "errorMessage"})
public class SyncError {
    private String sourceId;
    private String targetId;
    private boolean directory;
    private long size;
    private long transferStart;
    private int retryCount;
    private String errorMessage;

    @XmlElement(name = "SourceId")
    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    @XmlElement(name = "TargetId")
    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    @XmlElement(name = "Directory")
    public boolean isDirectory() {
        return directory;
    }

    public void setDirectory(boolean directory) {
        this.directory = directory;
    }

    @XmlElement(name = "Size")
    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    @XmlElement(name = "TransferStart")
    public long getTransferStart() {
        return transferStart;
    }

    public void setTransferStart(long transferStart) {
        this.transferStart = transferStart;
    }

    @XmlElement(name = "RetryCount")
    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    @XmlElement(name = "ErrorMessage")
    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public SyncError withSourceId(String sourceId) {
        setSourceId(sourceId);
        return this;
    }

    public SyncError withTargetId(String targetId) {
        setTargetId(targetId);
        return this;
    }

    public SyncError withDirectory(boolean directory) {
        setDirectory(directory);
        return this;
    }

    public SyncError withSize(long size) {
        setSize(size);
        return this;
    }

    public SyncError withTransferStart(long transferStart) {
        setTransferStart(transferStart);
        return this;
    }

    public SyncError withRetryCount(int retryCount) {
        setRetryCount(retryCount);
        return this;
    }

    public SyncError withErrorMessage(String errorMessage) {
        setErrorMessage(errorMessage);
        return this;
    }
}
