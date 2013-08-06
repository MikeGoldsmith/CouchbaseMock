/*
 * Copyright 2012 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.couchbase.mock.http;

import java.io.*;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.couchbase.mock.Bucket;
import org.couchbase.mock.harakiri.HarakiriMonitor;

/**
 * @author M. Nunberg
 */
class BucketsStreamingHandler implements Observer {

    private final OutputStream output;
    private final Bucket bucket;
    private final HarakiriMonitor monitor;
    private final Lock updateHandlerLock = new ReentrantLock();
    private final Condition condHasUpdatedConfig = updateHandlerLock.newCondition();
    private volatile boolean hasUpdatedConfig = false;
    private volatile boolean shouldTerminate = false;

    private static final byte[] chunkedDelimiter = "\n\n\n\n".getBytes();

    public BucketsStreamingHandler(HarakiriMonitor monitor, Bucket bucket, OutputStream output) {
        this.output = output;
        this.bucket = bucket;
        this.monitor = monitor;
    }

    private byte[] getConfigBytes() {
        return StateGrabber.getBucketJSON(bucket).getBytes();
    }

    private void writeConfigBytes(byte[] payload) throws IOException {
        output.write(payload);
        output.flush();
        output.write(chunkedDelimiter);
        output.flush();
    }

    @Override
    public void update(Observable o, Object arg) {
        updateHandlerLock.lock();
        try {
            hasUpdatedConfig = true;
            condHasUpdatedConfig.signalAll();
        } finally {
            updateHandlerLock.unlock();
        }
    }

    private boolean streamNewConfig() throws InterruptedException {
        updateHandlerLock.lock();
        try {
            while (shouldTerminate == false && hasUpdatedConfig == false) {
                condHasUpdatedConfig.await();
            }
            if (hasUpdatedConfig) {
                writeConfigBytes(getConfigBytes());
                hasUpdatedConfig = false;
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            shouldTerminate = false;
            return false;
        } finally {
            updateHandlerLock.unlock();
        }
    }

    public void startStreaming() throws IOException, InterruptedException {
        byte[] configBytes;
        bucket.configReadLock();
        configBytes = getConfigBytes();


        // Lock the updater lock, make sure updates are not received
        // before we send our initial (older) data.
        updateHandlerLock.lock();

        if (monitor != null) {
            monitor.addObserver(this);
        }

        // This can be unlocked, because we have wired the update() method.
        // Therefore, any changes will be placed AFTER our 'initial' frozen
        // snapshot.
        bucket.configReadUnlock();

        try {
            writeConfigBytes(configBytes);
        } finally {
            // Allow any subsequent updates to be sent
            updateHandlerLock.unlock();
        }

        while (streamNewConfig()) {
            //
        }

        /*
         * Error or some such
         */
        output.close();
    }
}
