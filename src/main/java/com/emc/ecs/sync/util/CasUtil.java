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
package com.emc.ecs.sync.util;

import com.filepool.fplibrary.FPLibraryException;

public class CasUtil {
    /**
     * This pattern is used to activate the CAS plugins.
     */
    public static final String URI_PATTERN = "^cas://([^/]*?)(:[0-9]+)?(,([^/]*?)(:[0-9]+)?)*\\?.*$";
    public static final String URI_PREFIX = "cas://";

    public static final String OPERATION_FETCH_QUERY_RESULT = "CasFetchQueryResult";
    public static final String OPERATION_OPEN_CLIP = "CasOpenClip";
    public static final String OPERATION_READ_CDF = "CasReadCdf";
    public static final String OPERATION_WRITE_CDF = "CasWriteCdf";
    public static final String OPERATION_STREAM_BLOB = "CasStreamBlob";
    public static final String OPERATION_WRITE_CLIP = "CasWriteClip";
    public static final String OPERATION_TOTAL = "TotalTime";

    private CasUtil() {
    }

    public static String generateRelativePath(String clipId) {
        return clipId + ".cdf";
    }

    public static String summarizeError(FPLibraryException e) {
        return String.format("CAS Error %s/%s: %s", e.getErrorCode(), e.getErrorString(), e.getMessage());
    }
}
