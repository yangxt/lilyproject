/*
 * Copyright 2010 Outerthought bvba
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
 */
package org.lilyproject.rowlog.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.lilyproject.rowlog.api.ProcessorNotifyObserver;
import org.lilyproject.rowlog.api.RowLog;
import org.lilyproject.rowlog.api.RowLogConfig;
import org.lilyproject.rowlog.api.RowLogConfigurationManager;
import org.lilyproject.rowlog.api.RowLogException;
import org.lilyproject.rowlog.api.RowLogMessage;
import org.lilyproject.rowlog.api.RowLogObserver;
import org.lilyproject.rowlog.api.RowLogProcessor;
import org.lilyproject.rowlog.api.RowLogShard;
import org.lilyproject.rowlog.api.RowLogSubscription;
import org.lilyproject.rowlog.api.SubscriptionsObserver;
import org.lilyproject.util.Logs;

public class RowLogProcessorImpl implements RowLogProcessor, RowLogObserver, SubscriptionsObserver, ProcessorNotifyObserver {
    private volatile boolean stop = true;
    protected final RowLog rowLog;
    private final RowLogShard shard;
    protected final Map<String, SubscriptionThread> subscriptionThreads = Collections.synchronizedMap(new HashMap<String, SubscriptionThread>());
    private RowLogConfigurationManager rowLogConfigurationManager;
    private Log log = LogFactory.getLog(getClass());
    private long lastNotify = -1;
    private RowLogConfig rowLogConfig;

    /*
     * Maximum expected clock skew between servers. In fact this is not just pure clock skew, but also
     * encompasses the delay between the moment of timestamp determination and actual insertion onto HBase.
     * The higher the skew, the more put-delete pairs of past messages that HBase will have to scan over.
     * The shorter the skew, the more chance messages will get stuck unprocessed, either due to clock skews
     * or due to slow processing of the put on the global queue, such as in case of HBase region recovery.
     * At the time of this writing, HBase checked this skew, allowing up to 30s:
     * https://issues.apache.org/jira/browse/HBASE-3168
     */
    private static final int MAX_CLOCK_SKEW_BETWEEN_SERVERS = 120000; // 2 minutes
    
    private final AtomicBoolean initialRowLogConfigLoaded = new AtomicBoolean(false);
    
    public RowLogProcessorImpl(RowLog rowLog, RowLogConfigurationManager rowLogConfigurationManager) {
        this.rowLog = rowLog;
        this.rowLogConfigurationManager = rowLogConfigurationManager;
        this.shard = rowLog.getShards().get(0); // TODO: For now we only work with one shard
    }

    public RowLog getRowLog() {
        return rowLog;
    }

    @Override
    protected synchronized void finalize() throws Throwable {
        stop();
        super.finalize();
    }
    
    public synchronized void start() throws InterruptedException {
        if (stop) {
            stop = false;
            initializeRowLogConfig();
            initializeSubscriptions();
            initializeNotifyObserver();
        }
    }

    private void initializeNotifyObserver() {
        rowLogConfigurationManager.addProcessorNotifyObserver(rowLog.getId(), shard.getId(), this);
    }

    protected void initializeSubscriptions() {
        rowLogConfigurationManager.addSubscriptionsObserver(rowLog.getId(), this);
    }

    private void initializeRowLogConfig() throws InterruptedException {
        rowLogConfigurationManager.addRowLogObserver(rowLog.getId(), this);
        synchronized (initialRowLogConfigLoaded) {
            while(!initialRowLogConfigLoaded.get()) {
                initialRowLogConfigLoaded.wait();
            }
        }
    }

    //  synchronized because we do not want to run this concurrently with the start/stop methods
    public synchronized void subscriptionsChanged(List<RowLogSubscription> newSubscriptions) {
        synchronized (subscriptionThreads) {
            if (!stop) {
                List<String> newSubscriptionIds = new ArrayList<String>();
                for (RowLogSubscription newSubscription : newSubscriptions) {
                    String subscriptionId = newSubscription.getId();
                    newSubscriptionIds.add(subscriptionId);
                    SubscriptionThread existingSubscriptionThread = subscriptionThreads.get(subscriptionId);
                    if (existingSubscriptionThread == null) {
                        if (log.isDebugEnabled()) {
                            log.debug("Starting subscription thread for new subscription " + newSubscription.getId());
                        }
                        SubscriptionThread subscriptionThread = startSubscriptionThread(newSubscription);
                        subscriptionThreads.put(subscriptionId, subscriptionThread);
                    } else if (!existingSubscriptionThread.getSubscription().equals(newSubscription)) {
                        if (log.isDebugEnabled()) {
                            log.debug("Stopping existing and starting new subscription thread for subscription "
                                    + newSubscription.getId());
                        }
                        stopSubscriptionThread(subscriptionId);
                        SubscriptionThread subscriptionThread = startSubscriptionThread(newSubscription);
                        subscriptionThreads.put(subscriptionId, subscriptionThread);
                    }
                }
                Iterator<String> iterator = subscriptionThreads.keySet().iterator();
                while (iterator.hasNext()) {
                    String subscriptionId = iterator.next();
                    if (!newSubscriptionIds.contains(subscriptionId)) {
                        if (log.isDebugEnabled()) {
                            log.debug("Stopping subscription thread for subscription " + subscriptionId);
                        }
                        stopSubscriptionThread(subscriptionId);
                        iterator.remove();
                    }
                }
            }
        }
    }

    protected SubscriptionThread startSubscriptionThread(RowLogSubscription subscription) {
        SubscriptionThread subscriptionThread = new SubscriptionThread(subscription);
        subscriptionThread.start();
        return subscriptionThread;
    }
    
    private void stopSubscriptionThread(String subscriptionId) {
        SubscriptionThread subscriptionThread = subscriptionThreads.get(subscriptionId);
        subscriptionThread.shutdown();
        try {
            Logs.logThreadJoin(subscriptionThread);
            subscriptionThread.join();
        } catch (InterruptedException e) {
        }
    }

    public synchronized void stop() {
        stop = true;
        stopRowLogConfig();
        stopSubscriptions();
        stopNotifyObserver();
        stopSubscriptionThreads();
    }

    private void stopSubscriptionThreads() {
        Collection<SubscriptionThread> threadsToStop;
        synchronized (subscriptionThreads) {
            threadsToStop = new ArrayList<SubscriptionThread>(subscriptionThreads.values());
            subscriptionThreads.clear();
        }
        for (SubscriptionThread thread : threadsToStop) {
            if (thread != null) {
                thread.shutdown();
            }
        }
        for (Thread thread : threadsToStop) {
            if (thread != null) {
                try {
                    if (thread.isAlive()) {
                        Logs.logThreadJoin(thread);
                        thread.join();
                    }
                } catch (InterruptedException e) {
                }
            }
        }
    }

    private void stopNotifyObserver() {
        rowLogConfigurationManager.removeProcessorNotifyObserver(rowLog.getId(), shard.getId());
    }

    protected void stopSubscriptions() {
        rowLogConfigurationManager.removeSubscriptionsObserver(rowLog.getId(), this);
    }

    private void stopRowLogConfig() {
        rowLogConfigurationManager.removeRowLogObserver(rowLog.getId(), this);
        synchronized (initialRowLogConfigLoaded) {
            initialRowLogConfigLoaded.set(false);
        }
    }

    public boolean isRunning(int subscriptionId) {
        return subscriptionThreads.get(subscriptionId) != null;
    }
    
    /**
     * Called when a message has been posted on the rowlog that needs to be processed by this RowLogProcessor. </p>
     * The notification will only be taken into account when a delay has passed since the previous notification.
     */
    synchronized public void notifyProcessor() {
    	long now = System.currentTimeMillis();
    	// Only consume the notification if the notifyDelay has expired to avoid too many wakeups
    	if ((lastNotify + rowLogConfig.getNotifyDelay()) <= now) { 
    		lastNotify = now;
	    	Collection<SubscriptionThread> threadsToWakeup;
	        synchronized (subscriptionThreads) {
	            threadsToWakeup = new HashSet<SubscriptionThread>(subscriptionThreads.values());
	        }
	        for (SubscriptionThread subscriptionThread : threadsToWakeup) {
	            subscriptionThread.wakeup();
	        }
    	}
    }

    protected class SubscriptionThread extends Thread {
        private long lastWakeup;
        private ProcessorMetrics metrics;
        private volatile boolean stopRequested = false; // do not rely only on Thread.interrupt since some libraries eat interruptions
        private MessagesWorkQueue messagesWorkQueue = new MessagesWorkQueue();
        private SubscriptionHandler subscriptionHandler;
        private final RowLogSubscription subscription;
        private boolean firstRun = true;

        public SubscriptionThread(RowLogSubscription subscription) {
            super("Row log SubscriptionThread for " + subscription.getId());
            this.subscription = subscription;
            this.metrics = new ProcessorMetrics(rowLog.getId()+"_"+subscription.getId());
            switch (subscription.getType()) {
            case VM:
                subscriptionHandler = new LocalListenersSubscriptionHandler(subscription.getId(), messagesWorkQueue, rowLog, rowLogConfigurationManager);
                break;
                
            case Netty:
                subscriptionHandler = new RemoteListenersSubscriptionHandler(subscription.getId(),  messagesWorkQueue, rowLog, rowLogConfigurationManager);
                break;

            case WAL:
                subscriptionHandler = new WalSubscriptionHandler(subscription.getId(), messagesWorkQueue, rowLog, rowLogConfigurationManager);
                break;
                
            default:
                break;
            }
        }
        
        public RowLogSubscription getSubscription() {
            return subscription;
        }
        
        public synchronized void wakeup() {
            metrics.wakeups.inc();
            lastWakeup = System.currentTimeMillis();
            this.notify();
        }
        
        @Override
        public synchronized void start() {
            stopRequested = false;
            subscriptionHandler.start();
            super.start();
        }
        
        public void shutdown() {
            stopRequested = true;
            subscriptionHandler.shutdown();
            interrupt();
        }

        public void run() {
            try {
                Long minimalTimestamp = null;
                while (!isInterrupted() && !stopRequested) {
                    String subscriptionId = subscription.getId();
                    try {
                        metrics.scans.inc();

                        long tsBeforeGetMessages = System.currentTimeMillis();
                        List<RowLogMessage> messages = shard.next(subscriptionId, minimalTimestamp);
                        metrics.scanDuration.inc(System.currentTimeMillis() - tsBeforeGetMessages);

                        if (log.isDebugEnabled()) {
                            log.debug(String.format("[%1$s - %2$s] Scanned with minimal timestamp of %3$s, got %4$s messages.",
                                    rowLog.getId(), subscriptionId, minimalTimestamp, messages.size()));
                        }

                        if (stopRequested) {
                            // Check if not stopped because HBase hides thread interruptions
                            return;
                        }

                        if (firstRun) {
                            firstRun = false;
                            if (messages.isEmpty()) {
                                // If on startup of this processor, we have no messages, we initialize the
                                // minimalTimestamp manually so that we would not always scan from the start
                                // of the table.
                                minimalTimestamp = tsBeforeGetMessages - MAX_CLOCK_SKEW_BETWEEN_SERVERS;

                                if (log.isDebugEnabled()) {
                                    log.debug(String.format("[%1$s - %2$s] On initial scan, got no messages from HBase, " +
                                            "setting minimal timestamp to %3$s", rowLog.getId(), subscriptionId, minimalTimestamp));
                                }
                            }
                        }

                        metrics.messagesPerScan.inc(messages != null ? messages.size() : 0);
						if (messages != null && !messages.isEmpty()) {
						    minimalTimestamp = messages.get(0).getTimestamp() - MAX_CLOCK_SKEW_BETWEEN_SERVERS;
                            for (RowLogMessage message : messages) {
                                if (stopRequested)
                                    return;

                                if (checkMinimalProcessDelay(message))
                                	break; // Rescan the messages since they might have been processed in the meanwhile

                                messagesWorkQueue.offer(message);
                            }
                        }

                        // If we had a full batch of messages, we will immediately request the next batch, without
                        // sleeping. If we got less, we sleep unless we received a wake-up signal after we started
                        // scanning for messages.
                        // Also: the minimalProcessDelay setting is not taken into account: as it currently is,
                        // this is only relevant for the WAL, which does not make use of the wake-up signal.
                        if (messages.size() < shard.getBatchSize() && lastWakeup < tsBeforeGetMessages) {
                            synchronized (this) {
                                // The timeout covers two cases:
                                //   (1) a safety fallback, in case a wake-up got lost or so
                                //   (2) the WAL, which does not make use of wake-ups
                                wait(rowLogConfig.getWakeupTimeout());
                            }
                        }

                        // It makes no sense to scan for new messages if the work-queue is still full. The messages
                        // which would be retrieved by the scan would be messages which are still in the work-queue
                        // anyway (minus those meanwhile consumed by the listeners). When no listeners are active,
                        // this can even lead to endless scan-loops (if work-queue-size >= batch-size and # messages
                        // in queue > work-queue-size). So we'll wait till the work-queue is almost empty.
                        messagesWorkQueue.waitOnRefillThreshold();

                    } catch (InterruptedException e) {
                        return;
                    } catch (RowLogException e) {
                        // The message will be retried later
                        log.info("Error processing message for subscription " + subscriptionId + " (message will be retried later).", e);
                    } catch (Throwable t) {
                        if (Thread.currentThread().isInterrupted())
                            return;
                        log.error("Error in subscription thread for " + subscriptionId, t);
                    }
                }
            } finally {
                metrics.shutdown();
            }
        }

        /**
         * Check if the message is old enough to be processed. If not, wait
         * until it is. Any other messages that might be in the queue to be
         * processed should (will) be younger and don't have to be processed yet
         * either.
         * 
         * @return true if we waited
         * @throws InterruptedException
         */
        private boolean checkMinimalProcessDelay(RowLogMessage message) throws InterruptedException {
            long now = System.currentTimeMillis();
            long messageTimestamp = message.getTimestamp();
            long waitAtLeastUntil = messageTimestamp + rowLogConfig.getMinimalProcessDelay();
            if (now < waitAtLeastUntil) {
                synchronized (this) {
                    wait(waitAtLeastUntil - now);
                }
                return true;
            }
        	return false;
        }
    }
    
    public void rowLogConfigChanged(RowLogConfig rowLogConfig) {
        this.rowLogConfig = rowLogConfig;
        if (!initialRowLogConfigLoaded.get()) {
            synchronized(initialRowLogConfigLoaded) {
                initialRowLogConfigLoaded.set(true);
                initialRowLogConfigLoaded.notifyAll();
            }
        }
    }
    
    protected boolean isMessageDone(RowLogMessage message, String subscriptionId) throws RowLogException {
        return rowLog.isMessageDone(message, subscriptionId);
    }
}
