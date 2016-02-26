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

import org.apache.storm.Config;
import org.apache.storm.cluster.IStormClusterState;
import org.apache.storm.generated.SupervisorInfo;
import org.apache.storm.utils.Time;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SupervisorHeartbeat implements Runnable {

    private IStormClusterState stormClusterState;
    private String supervisorId;
    private Map conf;
    private SupervisorInfo supervisorInfo;

    private SupervisorData supervisorData;

    public SupervisorHeartbeat(Map conf, SupervisorData supervisorData) {
        this.stormClusterState = supervisorData.getStormClusterState();
        this.supervisorId = supervisorData.getSupervisorId();
        this.supervisorData = supervisorData;
        this.conf = conf;
    }

    private SupervisorInfo update(Map conf, SupervisorData supervisorData) {
        supervisorInfo = new SupervisorInfo();
        supervisorInfo.set_time_secs(Time.currentTimeSecs());
        supervisorInfo.set_hostname(supervisorData.getHostName());
        supervisorInfo.set_assignment_id(supervisorData.getAssignmentId());

        List<Long> usedPorts = new ArrayList<>();
        usedPorts.addAll(supervisorData.getCurrAssignment().keySet());
        supervisorInfo.set_used_ports(usedPorts);
        List<Long> portList = new ArrayList<>();
        Object metas = supervisorData.getiSupervisor().getMetadata();
        if (metas != null) {
            for (Integer port : (List<Integer>) metas) {
                portList.add(port.longValue());
            }
        }
        supervisorInfo.set_meta(portList);
        supervisorInfo.set_scheduler_meta((Map<String, String>) conf.get(Config.SUPERVISOR_SCHEDULER_META));
        supervisorInfo.set_uptime_secs(supervisorData.getUpTime().upTime());
        supervisorInfo.set_version(supervisorData.getStormVersion());
        supervisorInfo.set_resources_map(mkSupervisorCapacities(conf));
        return supervisorInfo;
    }

    private Map<String, Double> mkSupervisorCapacities(Map conf) {
        Map<String, Double> ret = new HashMap<String, Double>();
        Double mem = (double) (conf.get(Config.SUPERVISOR_MEMORY_CAPACITY_MB));
        ret.put(Config.SUPERVISOR_MEMORY_CAPACITY_MB, mem);
        Double cpu = (double) (conf.get(Config.SUPERVISOR_CPU_CAPACITY));
        ret.put(Config.SUPERVISOR_CPU_CAPACITY, cpu);
        return ret;
    }

    @Override
    public void run() {
        SupervisorInfo supervisorInfo = update(conf, supervisorData);
        stormClusterState.supervisorHeartbeat(supervisorId, supervisorInfo);
    }
}
