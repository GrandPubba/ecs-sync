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
package com.emc.ecs.sync.service;

import com.emc.ecs.sync.SyncPlugin;
import com.emc.ecs.sync.filter.SyncFilter;
import com.emc.ecs.sync.model.ObjectStatus;
import com.emc.ecs.sync.model.object.SyncObject;
import com.emc.ecs.sync.source.SyncSource;
import com.emc.ecs.sync.target.SyncTarget;
import com.emc.ecs.sync.util.Function;
import com.emc.ecs.sync.util.TimingUtil;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.Closeable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public abstract class DbService implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(DbService.class);

    public static final String OPERATION_OBJECT_QUERY = "ObjectQuery";
    public static final String OPERATION_OBJECT_UPDATE = "ObjectUpdate";
    public static final String OPERATION_LIST_ERRORS = "ListErrors";

    public static final String DEFAULT_OBJECTS_TABLE_NAME = "objects";
    public static final int DEFAULT_MAX_ERROR_SIZE = 2048;

    protected SyncPlugin timingPlugin = new DatabasePlugin();
    protected String objectsTableName = DEFAULT_OBJECTS_TABLE_NAME;
    protected int maxErrorSize = DEFAULT_MAX_ERROR_SIZE;
    private JdbcTemplate jdbcTemplate;
    private boolean initialized = false;

    /**
     * Implementations must provide a method to wipe out their database. This may mean deleting a .db file (Sqlite) or
     * dropping relevant tables, etc. Be aware that this operation may be done after {@link #close()} is called
     */
    public abstract void deleteDatabase();

    protected abstract JdbcTemplate createJdbcTemplate();

    protected abstract void createTable();

    /**
     * Be sure we close resources before GC
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    public void setStatus(final SyncObject object, final ObjectStatus status, final String error, final boolean newRow) {
        initCheck();
        final String dateField = getDateFieldForStatus(status);
        final Date dateValue = getDateValueForStatus(status);
        Long contentLength = null;
        Date mtime = null;
        try {
            contentLength = object.getMetadata().getContentLength();
            mtime = object.getMetadata().getModificationTime();
        } catch (Throwable t) {
            log.info("could not pull metadata from object {}: {}", object, t.toString());
        }
        final Long fContentLength = contentLength;
        final Date fMtime = mtime;
        TimingUtil.time(timingPlugin, OPERATION_OBJECT_UPDATE, new Function<Void>() {
            @Override
            public Void call() {
                if (newRow) {
                    String insert = SyncRecord.insert(objectsTableName, SyncRecord.SOURCE_ID, SyncRecord.TARGET_ID,
                            SyncRecord.IS_DIRECTORY, SyncRecord.SIZE, SyncRecord.MTIME, SyncRecord.STATUS,
                            dateField, SyncRecord.RETRY_COUNT, SyncRecord.ERROR_MESSAGE);
                    getJdbcTemplate().update(insert, object.getSourceIdentifier(), object.getTargetIdentifier(),
                            object.isDirectory(), fContentLength, getDateParam(fMtime), status.getValue(),
                            getDateParam(dateValue), object.getFailureCount(), fitString(error, maxErrorSize));
                } else {
                    // don't want to overwrite last error message unless there is a new error message
                    List<String> fields = new ArrayList<>(Arrays.asList(SyncRecord.TARGET_ID,
                            SyncRecord.IS_DIRECTORY, SyncRecord.SIZE, SyncRecord.MTIME, SyncRecord.STATUS,
                            dateField, SyncRecord.RETRY_COUNT));
                    if (error != null) fields.add(SyncRecord.ERROR_MESSAGE);
                    String update = SyncRecord.updateBySourceId(objectsTableName, fields.toArray(new String[fields.size()]));

                    List<Object> params = new ArrayList<>(Arrays.asList(object.getTargetIdentifier(),
                            object.isDirectory(), fContentLength, getDateParam(fMtime), status.getValue(),
                            getDateParam(dateValue), object.getFailureCount()));
                    if (error != null) params.add(fitString(error, maxErrorSize));
                    params.add(object.getSourceIdentifier());

                    getJdbcTemplate().update(update, params.toArray());
                }
                return null;
            }
        });
    }

    public SyncRecord getSyncRecord(final SyncObject object) {
        initCheck();
        return TimingUtil.time(timingPlugin, OPERATION_OBJECT_QUERY, new Function<SyncRecord>() {
            @Override
            public SyncRecord call() {
                try {
                    return getJdbcTemplate().queryForObject(SyncRecord.selectBySourceId(objectsTableName),
                            new Mapper(), object.getSourceIdentifier());
                } catch (IncorrectResultSizeDataAccessException e) {
                    return null;
                }
            }
        });
    }

    public Iterable<SyncRecord> getSyncErrors() {
        initCheck();
        return TimingUtil.time(timingPlugin, OPERATION_LIST_ERRORS, new Function<Iterable<SyncRecord>>() {
            @Override
            public Iterable<SyncRecord> call() {
                return new Iterable<SyncRecord>() {
                    @Override
                    public Iterator<SyncRecord> iterator() {
                        return new RowIterator<>(getJdbcTemplate().getDataSource(), new Mapper(),
                                SyncRecord.selectErrors(objectsTableName));
                    }
                };
            }
        });
    }

    protected synchronized void initCheck() {
        if (!initialized) {
            jdbcTemplate = createJdbcTemplate();
            createTable();
            initialized = true;
        }
    }

    /**
     * Be sure to override in implementations to close the datasource completely, then call super.close()
     */
    @Override
    public void close() {
        jdbcTemplate = null;
    }

    protected String getDateFieldForStatus(ObjectStatus status) {
        if (status == ObjectStatus.InTransfer) return "transfer_start";
        else if (status == ObjectStatus.Transferred) return "transfer_complete";
        else if (status == ObjectStatus.InVerification) return "verify_start";
        else return "verify_complete";
    }

    protected Date getDateValueForStatus(ObjectStatus status) {
        if (Arrays.asList(ObjectStatus.InTransfer, ObjectStatus.Transferred, ObjectStatus.InVerification, ObjectStatus.Verified)
                .contains(status))
            return new Date();
        else return null;
    }

    protected String fitString(String string, int size) {
        if (string == null) return null;
        if (string.length() > size) {
            return string.substring(0, size);
        }
        return string;
    }

    protected JdbcTemplate getJdbcTemplate() {
        if (jdbcTemplate == null)
            throw new UnsupportedOperationException("this service has closed all DB connections");
        return jdbcTemplate;
    }

    protected boolean hasColumn(ResultSet rs, String name) {
        try {
            rs.findColumn(name);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    protected boolean hasStringColumn(ResultSet rs, String name) throws SQLException {
        if (hasColumn(rs, name)) {
            String value = rs.getString(name);
            return !rs.wasNull() && value != null;
        }
        return false;
    }

    protected boolean hasLongColumn(ResultSet rs, String name) throws SQLException {
        if (hasColumn(rs, name)) {
            rs.getLong(name);
            return !rs.wasNull();
        }
        return false;
    }

    protected boolean hasBooleanColumn(ResultSet rs, String name) throws SQLException {
        if (hasColumn(rs, name)) {
            rs.getBoolean(name);
            return !rs.wasNull();
        }
        return false;
    }

    protected boolean hasDateColumn(ResultSet rs, String name) throws SQLException {
        if (hasColumn(rs, name)) {
            rs.getDate(name);
            return !rs.wasNull();
        }
        return false;
    }

    protected Date getResultDate(ResultSet rs, String name) throws SQLException {
        return rs.getDate(name);
    }

    protected Object getDateParam(Date date) {
        return date;
    }

    public String getObjectsTableName() {
        return objectsTableName;
    }

    public void setObjectsTableName(String objectsTableName) {
        this.objectsTableName = objectsTableName;
    }

    public int getMaxErrorSize() {
        return maxErrorSize;
    }

    public void setMaxErrorSize(int maxErrorSize) {
        this.maxErrorSize = maxErrorSize;
    }

    public DbService withObjectsTableName(String objectsTableName) {
        setObjectsTableName(objectsTableName);
        return this;
    }

    public DbService withMaxErrorSize(int maxErrorSize) {
        setMaxErrorSize(maxErrorSize);
        return this;
    }

    /**
     * Uses best-effort to populate fields based on the available columns in the result set.  If a field
     * is not present in the result set, the field is left null or whatever its default value is.
     */
    public class Mapper implements RowMapper<SyncRecord> {
        @Override
        public SyncRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            SyncRecord record = new SyncRecord();

            if (!hasColumn(rs, SyncRecord.SOURCE_ID))
                throw new IllegalArgumentException("result set does not have a column named " + SyncRecord.SOURCE_ID);
            record.setSourceId(rs.getString(SyncRecord.SOURCE_ID));

            if (hasStringColumn(rs, SyncRecord.TARGET_ID)) record.setTargetId(rs.getString(SyncRecord.TARGET_ID));
            if (hasBooleanColumn(rs, SyncRecord.IS_DIRECTORY))
                record.setIsDirectory(rs.getBoolean(SyncRecord.IS_DIRECTORY));
            if (hasLongColumn(rs, SyncRecord.SIZE)) record.setSize(rs.getLong(SyncRecord.SIZE));
            if (hasDateColumn(rs, SyncRecord.MTIME)) record.setMtime(getResultDate(rs, SyncRecord.MTIME));
            if (hasStringColumn(rs, SyncRecord.STATUS))
                record.setStatus(ObjectStatus.fromValue(rs.getString(SyncRecord.STATUS)));
            if (hasDateColumn(rs, SyncRecord.TRANSFER_START))
                record.setTransferStart(getResultDate(rs, SyncRecord.TRANSFER_START));
            if (hasDateColumn(rs, SyncRecord.TRANSFER_COMPLETE))
                record.setTransferComplete(getResultDate(rs, SyncRecord.TRANSFER_COMPLETE));
            if (hasDateColumn(rs, SyncRecord.VERIFY_START))
                record.setVerifyStart(getResultDate(rs, SyncRecord.VERIFY_START));
            if (hasDateColumn(rs, SyncRecord.VERIFY_COMPLETE))
                record.setVerifyComplete(getResultDate(rs, SyncRecord.VERIFY_COMPLETE));
            if (hasLongColumn(rs, SyncRecord.RETRY_COUNT)) record.setRetryCount(rs.getInt(SyncRecord.RETRY_COUNT));
            if (hasStringColumn(rs, SyncRecord.ERROR_MESSAGE))
                record.setErrorMessage(rs.getString(SyncRecord.ERROR_MESSAGE));

            return record;
        }
    }

    // purely for timing functions
    protected class DatabasePlugin extends SyncFilter {
        @Override
        public String getActivationName() {
            return null;
        }

        @Override
        public void filter(SyncObject obj) {
        }

        @Override
        public SyncObject reverseFilter(SyncObject obj) {
            return null;
        }

        @Override
        public String getName() {
            return "Database";
        }

        @Override
        public String getDocumentation() {
            return null;
        }

        @Override
        public Options getCustomOptions() {
            return null;
        }

        @Override
        protected void parseCustomOptions(CommandLine line) {
        }

        @Override
        public void configure(SyncSource source, Iterator<SyncFilter> filters, SyncTarget target) {
        }
    }
}
