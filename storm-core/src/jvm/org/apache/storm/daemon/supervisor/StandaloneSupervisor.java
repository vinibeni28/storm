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
import org.apache.storm.scheduler.ISupervisor;
import org.apache.storm.utils.LocalState;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public class StandaloneSupervisor implements ISupervisor {

    private String supervisorId;

    private Map conf;

    @Override
    public void prepare(Map stormConf, String schedulerLocalDir) {
        try {
            LocalState localState = new LocalState(schedulerLocalDir);
            String supervisorId = localState.getSupervisorId();
            if (supervisorId == null) {
                supervisorId = UUID.randomUUID().toString();
                localState.setSupervisorId(supervisorId);
            }
            this.conf = stormConf;
            this.supervisorId = supervisorId;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getSupervisorId() {
        return supervisorId;
    }

    @Override
    public String getAssignmentId() {
        return supervisorId;
    }

    @Override
    // @return is vector which need be converted to be int
    public Object getMetadata() {
        Object ports = conf.get(Config.SUPERVISOR_SLOTS_PORTS);
        return ports;
    }

    @Override
    public boolean confirmAssigned(int port) {
        return true;
    }

    @Override
    public void killedWorker(int port) {

    }

    @Override
    public void assigned(Collection<Integer> ports) {

    }
}