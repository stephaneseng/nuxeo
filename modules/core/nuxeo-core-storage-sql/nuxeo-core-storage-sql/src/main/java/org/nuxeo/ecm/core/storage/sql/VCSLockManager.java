/*
 * (C) Copyright 2006-2020 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.storage.sql;

import static org.nuxeo.ecm.core.storage.sql.Model.LOCK_CREATED_KEY;
import static org.nuxeo.ecm.core.storage.sql.Model.LOCK_OWNER_KEY;
import static org.nuxeo.ecm.core.storage.sql.Model.LOCK_TABLE_NAME;
import static org.nuxeo.ecm.core.storage.sql.Model.MAIN_KEY;

import java.io.Serializable;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.ConcurrentUpdateException;
import org.nuxeo.ecm.core.api.Lock;
import org.nuxeo.ecm.core.api.LockException;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.cache.Cache;
import org.nuxeo.ecm.core.cache.CacheService;
import org.nuxeo.ecm.core.model.LockManager;
import org.nuxeo.ecm.core.storage.sql.jdbc.SQLInfo;
import org.nuxeo.ecm.core.storage.sql.jdbc.SQLInfo.SQLInfoSelect;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Column;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.datasource.ConnectionHelper;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * Manager of locks stored in the repository SQL database.
 */
public class VCSLockManager implements LockManager {

    private static final Log log = LogFactory.getLog(VCSLockManager.class);

    public static final int LOCK_RETRIES = 10;

    public static final long LOCK_SLEEP_DELAY = 1; // 1 ms

    public static final long LOCK_SLEEP_INCREMENT = 50; // add 50 ms each time

    protected static final Lock NULL_LOCK = new Lock(null, null);

    protected final String repositoryName;

    protected final Model model;

    protected final SQLInfo sqlInfo;

    protected final Cache lockCache;

    protected Connection connection;

    /**
     * Creates a lock manager for the given repository.
     * <p>
     * {@link #closeLockManager()} must be called when done with the lock manager.
     *
     * @since 9.3
     */
    public VCSLockManager(RepositoryImpl repository) {
        repositoryName = repository.getName();
        model = repository.getModel();
        sqlInfo = repository.getSQLInfo();
        String cacheName = repositoryName + "/locks";
        lockCache = getCache(cacheName);
        connection = runWithoutTransaction(this::openConnection);
    }

    @Override
    public void closeLockManager() {
        runWithoutTransaction(this::closeConnection);
    }

    protected Connection openConnection() {
        String dataSourceName = "repository_" + repositoryName;
        try {
            // open connection in noSharing mode
            Connection conn = ConnectionHelper.getConnection(dataSourceName, true);
            sqlInfo.dialect.performPostOpenStatements(conn);
            return conn;
        } catch (SQLException e) {
            throw new NuxeoException("Cannot connect to database: " + repositoryName, e);
        }
    }

    protected void closeConnection() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.error(e, e);
        }
    }

    protected static void runWithoutTransaction(Runnable runnable) {
        runWithoutTransaction(() -> { runnable.run(); return null; });
    }

    // completely stops the current transaction while running something
    protected static <R> R runWithoutTransaction(Supplier<R> supplier) {
        boolean rollback = TransactionHelper.isTransactionMarkedRollback();
        boolean hasTransaction = TransactionHelper.isTransactionActiveOrMarkedRollback();
        if (hasTransaction) {
            TransactionHelper.commitOrRollbackTransaction();
        }
        boolean completedAbruptly = true;
        try {
            R result = supplier.get();
            completedAbruptly = false;
            return result;
        } finally {
            if (hasTransaction) {
                try {
                    TransactionHelper.startTransaction();
                } finally {
                    if (completedAbruptly || rollback) {
                        TransactionHelper.setTransactionRollbackOnly();
                    }
                }
            }
        }
    }

    protected Cache getCache(String cacheName) {
        CacheService cacheService = Framework.getService(CacheService.class);
        Cache cache = cacheService.getCache(cacheName);
        if (cache == null) {
            cacheService.registerCache(cacheName);
            cache = cacheService.getCache(cacheName);
        }
        return cache;
    }

    protected Serializable idFromString(String id) {
        return model.idFromString(id);
    }

    @Override
    public Lock getLock(final String id) {
        Lock lock;
        if ((lock = (Lock) lockCache.get(id)) != null) {
            return lock == NULL_LOCK ? null : lock;
        }
        lock = readLock(idFromString(id));
        lockCache.put(id, lock == null ? NULL_LOCK : lock);
        return lock;
    }

    @Override
    public Lock setLock(String id, Lock lock) {
        // We don't call addSuppressed() on an existing exception
        // because constructing it beforehand when it most likely
        // won't be needed is expensive.
        List<Throwable> suppressed = new ArrayList<>(0);
        long sleepDelay = LOCK_SLEEP_DELAY;
        for (int i = 0; i < LOCK_RETRIES; i++) {
            if (i > 0) {
                log.debug("Retrying lock on " + id + ": try " + (i + 1));
            }
            try {
                return setLockInternal(id, lock);
            } catch (NuxeoException e) {
                suppressed.add(e);
                if (shouldRetry(e)) {
                    // cluster: two simultaneous inserts
                    // retry
                    try {
                        Thread.sleep(sleepDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                    sleepDelay += LOCK_SLEEP_INCREMENT;
                    continue;
                }
                // not something to retry
                NuxeoException exception = new NuxeoException(e);
                for (Throwable t : suppressed) {
                    exception.addSuppressed(t);
                }
                throw exception;
            }
        }
        LockException exception = new LockException(
                "Failed to lock " + id + ", too much concurrency (tried " + LOCK_RETRIES + " times)");
        for (Throwable t : suppressed) {
            exception.addSuppressed(t);
        }
        throw exception;
    }

    protected void checkConcurrentUpdate(Throwable e) {
        if (sqlInfo.dialect.isConcurrentUpdateException(e)) {
            log.debug(e, e);
            // don't keep the original message, as it may reveal database-level info
            throw new ConcurrentUpdateException("Concurrent update", e);
        }
    }

    /**
     * Does the exception mean that we should retry the transaction?
     */
    protected boolean shouldRetry(Exception e) {
        if (e instanceof ConcurrentUpdateException) {
            return true;
        }
        Throwable t = e.getCause();
        if (t instanceof BatchUpdateException && t.getCause() != null) {
            t = t.getCause();
        }
        return t instanceof SQLException && shouldRetry((SQLException) t);
    }

    protected boolean shouldRetry(SQLException e) {
        String sqlState = e.getSQLState();
        if ("23000".equals(sqlState)) {
            // MySQL: Duplicate entry ... for key ...
            // Oracle: unique constraint ... violated
            // SQL Server: Violation of PRIMARY KEY constraint
            return true;
        }
        if ("23001".equals(sqlState)) {
            // H2: Unique index or primary key violation
            return true;
        }
        if ("23505".equals(sqlState)) {
            // PostgreSQL: duplicate key value violates unique constraint
            return true;
        }
        if ("S0003".equals(sqlState) || "S0005".equals(sqlState)) {
            // SQL Server: Snapshot isolation transaction aborted due to update
            // conflict
            return true;
        }
        return false;
    }

    protected Lock setLockInternal(String id, Lock lock) {
        Lock oldLock;
        if ((oldLock = (Lock) lockCache.get(id)) != null && oldLock != NULL_LOCK) {
            return oldLock;
        }
        oldLock = writeLock(idFromString(id), lock);
        if (oldLock == null) {
            lockCache.put(id, lock == null ? NULL_LOCK : lock);
        }
        return oldLock;
    }

    @Override
    public Lock removeLock(final String id, final String owner) {
        Lock oldLock = null;
        if ((oldLock = (Lock) lockCache.get(id)) == NULL_LOCK) {
            return null;
        }
        if (oldLock != null && !LockManager.canLockBeRemoved(oldLock.getOwner(), owner)) {
            // existing mismatched lock, flag failure
            oldLock = new Lock(oldLock, true);
        } else {
            if (oldLock == null) {
                oldLock = deleteLock(idFromString(id), owner, false);
            } else {
                // we know the previous lock, we can force
                deleteLock(idFromString(id), owner, true);
            }
        }
        if (oldLock != null && oldLock.getFailed()) {
            // failed, but we now know the existing lock
            lockCache.put(id, new Lock(oldLock, false));
        } else {
            lockCache.put(id, NULL_LOCK);
        }
        return oldLock;
    }

    /*
     * ----- JDBC -----
     */

    protected Lock readLock(Serializable id) {
        SQLInfoSelect select = sqlInfo.selectFragmentById.get(LOCK_TABLE_NAME);
        try (PreparedStatement ps = connection.prepareStatement(select.sql)) {
            for (Column column : select.whereColumns) {
                String key = column.getKey();
                if (MAIN_KEY.equals(key)) {
                    column.setToPreparedStatement(ps, 1, id);
                } else {
                    throw new NuxeoException(key);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String owner = null;
                Calendar created = null;
                int i = 1;
                for (Column column : select.whatColumns) {
                    String key = column.getKey();
                    Serializable value = column.getFromResultSet(rs, i++);
                    if (LOCK_OWNER_KEY.equals(key)) {
                        owner = (String) value;
                    } else if (LOCK_CREATED_KEY.equals(key)) {
                        created = (Calendar) value;
                    } else {
                        throw new NuxeoException(key);
                    }
                }
                return new Lock(owner, created);
            }
        } catch (SQLException e) {
            checkConcurrentUpdate(e);
            throw new NuxeoException("Could not select: " + select.sql, e);
        }
    }

    protected Lock writeLock(final Serializable id, final Lock lock) {
        Lock oldLock = readLock(id);
        if (oldLock != null) {
            return oldLock;
        }
        String sql = sqlInfo.getInsertSql(LOCK_TABLE_NAME);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            for (Column column : sqlInfo.getInsertColumns(LOCK_TABLE_NAME)) {
                String key = column.getKey();
                Serializable value;
                if (MAIN_KEY.equals(key)) {
                    value = id;
                } else if (LOCK_OWNER_KEY.equals(key)) {
                    value = lock.getOwner();
                } else if (LOCK_CREATED_KEY.equals(key)) {
                    value = lock.getCreated();
                } else {
                    throw new NuxeoException(key);
                }
                column.setToPreparedStatement(ps, i++, value);
            }
            ps.execute();
        } catch (SQLException e) {
            checkConcurrentUpdate(e);
            throw new NuxeoException("Could not insert: " + sql, e);
        }
        return null;
    }

    protected Lock deleteLock(Serializable id, String owner, boolean force) {
        Lock oldLock = force ? null : readLock(id);
        if (!force && owner != null) {
            if (oldLock == null) {
                // not locked, nothing to do
                return null;
            }
            if (!LockManager.canLockBeRemoved(oldLock.getOwner(), owner)) {
                // existing mismatched lock, flag failure
                return new Lock(oldLock, true);
            }
        }
        if (force || oldLock != null) {
            deleteLock(id);
        }
        return oldLock;
    }

    protected void deleteLock(Serializable id) {
        String sql = sqlInfo.getDeleteSql(LOCK_TABLE_NAME, 1);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            sqlInfo.dialect.setId(ps, 1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            checkConcurrentUpdate(e);
            throw new NuxeoException("Could not delete: " + sql, e);
        }
    }

    @Override
    public void clearLockManagerCaches() {
        lockCache.invalidateAll();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + '(' + repositoryName + ')';
    }

}
