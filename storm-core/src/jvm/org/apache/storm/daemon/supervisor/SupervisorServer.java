/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.daemon.supervisor;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import org.apache.commons.io.FileUtils;
import org.apache.storm.Config;
import org.apache.storm.StormTimer;
import org.apache.storm.command.HealthCheck;
import org.apache.storm.daemon.metrics.MetricsUtils;
import org.apache.storm.daemon.metrics.reporters.PreparableReporter;
import org.apache.storm.event.EventManagerImp;
import org.apache.storm.localizer.Localizer;
import org.apache.storm.messaging.IContext;
import org.apache.storm.scheduler.ISupervisor;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.Utils;
import org.apache.storm.utils.VersionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InterruptedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SupervisorServer extends ShutdownWork {
    private static Logger LOG = LoggerFactory.getLogger(SupervisorServer.class);

    /**
     * in local state, supervisor stores who its current assignments are another thread launches events to restart any dead processes if necessary
     * 
     * @param conf
     * @param sharedContext
     * @param iSupervisor
     * @return
     * @throws Exception
     */
    private SupervisorManger mkSupervisor(final Map conf, IContext sharedContext, ISupervisor iSupervisor) throws Exception {
        SupervisorManger supervisorManger = null;
        try {
            LOG.info("Starting Supervisor with conf {}", conf);
            iSupervisor.prepare(conf, ConfigUtils.supervisorIsupervisorDir(conf));
            String path = ConfigUtils.supervisorTmpDir(conf);
            FileUtils.cleanDirectory(new File(path));

            final SupervisorData supervisorData = new SupervisorData(conf, sharedContext, iSupervisor);
            Localizer localizer = supervisorData.getLocalizer();

            SupervisorHeartbeat hb = new SupervisorHeartbeat(conf, supervisorData);
            hb.run();
            // should synchronize supervisor so it doesn't launch anything after being down (optimization)
            Integer heartbeatFrequency = (Integer) conf.get(Config.SUPERVISOR_HEARTBEAT_FREQUENCY_SECS);
            supervisorData.getHeartbeatTimer().scheduleRecurring(0, heartbeatFrequency, hb);

            Set<String> downdedStormId = SupervisorUtils.readDownLoadedStormIds(conf);
            for (String stormId : downdedStormId) {
                SupervisorUtils.addBlobReferences(localizer, stormId, conf);
            }
            // do this after adding the references so we don't try to clean things being used
            localizer.startCleaner();

            EventManagerImp syncSupEventManager = new EventManagerImp(false);
            EventManagerImp syncProcessManager = new EventManagerImp(false);
            SyncProcessEvent syncProcessEvent = new SyncProcessEvent(supervisorData);
            SyncSupervisorEvent syncSupervisorEvent = new SyncSupervisorEvent(supervisorData, syncProcessEvent, syncSupEventManager, syncProcessManager);
            UpdateBlobs updateBlobsThread = new UpdateBlobs(supervisorData);
            RunProfilerActions runProfilerActionThread = new RunProfilerActions(supervisorData);

            if ((Boolean) conf.get(Config.SUPERVISOR_ENABLE)) {
                StormTimer eventTimer = supervisorData.getEventTimer();
                // This isn't strictly necessary, but it doesn't hurt and ensures that the machine stays up
                // to date even if callbacks don't all work exactly right
                eventTimer.scheduleRecurring(0, 10, new EventManagerPushCallback(syncSupervisorEvent, syncSupEventManager));

                eventTimer.scheduleRecurring(0, (Integer) conf.get(Config.SUPERVISOR_MONITOR_FREQUENCY_SECS),
                        new EventManagerPushCallback(syncProcessEvent, syncProcessManager));

                // Blob update thread. Starts with 30 seconds delay, every 30 seconds
                supervisorData.getBlobUpdateTimer().scheduleRecurring(30, 30, new EventManagerPushCallback(updateBlobsThread, syncSupEventManager));

                // supervisor health check
                eventTimer.scheduleRecurring(300, 300, new Runnable() {
                    @Override
                    public void run() {
                        int healthCode = HealthCheck.healthCheck(conf);
                        Collection<String> workerIds = SupervisorUtils.supervisorWorkerIds(conf);
                        if (healthCode != 0) {
                            for (String workerId : workerIds) {
                                try {
                                    shutWorker(supervisorData, workerId);
                                } catch (Exception e) {
                                    throw Utils.wrapInRuntime(e);
                                }
                            }
                        }
                    }
                });

                // Launch a thread that Runs profiler commands . Starts with 30 seconds delay, every 30 seconds
                eventTimer.scheduleRecurring(30, 30, new EventManagerPushCallback(runProfilerActionThread, syncSupEventManager));
            }
            supervisorManger = new SupervisorManger(supervisorData, syncSupEventManager, syncProcessManager);
        } catch (Throwable t) {
            if (Utils.exceptionCauseIsInstanceOf(InterruptedIOException.class, t)) {
                throw t;
            } else if (Utils.exceptionCauseIsInstanceOf(InterruptedException.class, t)) {
                throw t;
            } else {
                LOG.error("Error on initialization of server supervisor");
                Utils.exitProcess(13, "Error on initialization");
            }
        }
        return supervisorManger;
    }

    /**
     * start local supervisor
     */
    public void localLaunch() {
        LOG.info("Starting supervisor for storm version '{}'.", VersionInfo.getVersion());
        SupervisorManger supervisorManager;
        try {
            Map<Object, Object> conf = Utils.readStormConfig();
            if (!ConfigUtils.isLocalMode(conf)) {
                throw new IllegalArgumentException("Cannot start server in distribute mode!");
            }
            ISupervisor iSupervisor = new StandaloneSupervisor();
            supervisorManager = mkSupervisor(conf, null, iSupervisor);
            if (supervisorManager != null)
                Utils.addShutdownHookWithForceKillIn1Sec(supervisorManager);
        } catch (Exception e) {
            LOG.error("Failed to start supervisor\n", e);
            System.exit(1);
        }
    }

    /**
     * start distribute supervisor
     */
    private void distributeLaunch() {
        LOG.info("Starting supervisor for storm version '{}'.", VersionInfo.getVersion());
        SupervisorManger supervisorManager;
        try {
            Map<Object, Object> conf = Utils.readStormConfig();
            if (ConfigUtils.isLocalMode(conf)) {
                throw new IllegalArgumentException("Cannot start server in local mode!");
            }
            ISupervisor iSupervisor = new StandaloneSupervisor();
            supervisorManager = mkSupervisor(conf, null, iSupervisor);
            if (supervisorManager != null)
                Utils.addShutdownHookWithForceKillIn1Sec(supervisorManager);
            registerWorkerNumGauge("drpc:num-execute-http-requests", conf);
            startMetricsReporters(conf);
        } catch (Exception e) {
            LOG.error("Failed to start supervisor\n", e);
            System.exit(1);
        }
    }

    // To be removed
    private void registerWorkerNumGauge(String name, final Map conf) {
        MetricRegistry metricRegistry = new MetricRegistry();
        metricRegistry.remove(name);
        metricRegistry.register(name, new Gauge<Integer>() {
            @Override
            public Integer getValue() {
                Collection<String> pids = Utils.readDirContents(ConfigUtils.workerRoot(conf));
                return pids.size();
            }
        });
    }

    // To be removed
    private void startMetricsReporters(Map conf) {
        List<PreparableReporter> preparableReporters = MetricsUtils.getPreparableReporters(conf);
        for (PreparableReporter reporter : preparableReporters) {
            reporter.prepare(new MetricRegistry(), conf);
            reporter.start();
        }
        LOG.info("Started statistics report plugin...");
    }

    /**
     * supervisor daemon enter entrance
     *
     * @param args
     */
    public static void main(String[] args) {
        Utils.setupDefaultUncaughtExceptionHandler();
        SupervisorServer instance = new SupervisorServer();
        instance.distributeLaunch();
    }
}
