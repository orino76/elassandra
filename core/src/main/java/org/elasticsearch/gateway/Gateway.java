/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.gateway;

import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.service.StorageService;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elassandra.NoPersistedMetaDataException;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateApplier;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;

import java.util.Map;

public class Gateway extends AbstractComponent implements ClusterStateApplier {

    private final ClusterService clusterService;

    public Gateway(Settings settings, ClusterService clusterService) {
        super(settings);
        this.clusterService = clusterService;
        //clusterService.addLowPriorityApplier(this);
    }

    public void performStateRecovery(final GatewayStateRecoveredListener listener) throws GatewayException {
        ClusterState.Builder builder = ClusterState.builder(clusterService.state());
        
        final boolean userKeyspaceAvailable = Keyspace.isInitialized();
        logger.debug("performing recovery from metadata %s", userKeyspaceAvailable ? "table":"schema");
        
        MetaData metadata;
        try {
            if (userKeyspaceAvailable) {
                // recover from elastic_admin.metadata
                if (StorageService.instance.isJoined()) {
                    metadata = clusterService.readMetaDataAsRow(ConsistencyLevel.ONE);
                } else {
                    metadata = clusterService.readInternalMetaDataAsRow();
                }
            } else {
                // recover from CQL schema
                metadata = clusterService.readMetaDataAsComment();
            }
        } catch (NoPersistedMetaDataException |ActionRequestValidationException e) {
            logger.trace((Supplier<?>) () -> new ParameterizedMessage("Cannot read metadata from metadata %s", userKeyspaceAvailable ? "table":"schema"), e);
            metadata = clusterService.state().metaData();
            if (metadata.clusterUUID().equals("_na_")) {
                metadata = MetaData.builder(metadata).clusterUUID(clusterService.localNode().getId()).build();
            }
        }

        listener.onSuccess( builder.metaData(metadata).build() );
    }

    private void logUnknownSetting(String settingType, Map.Entry<String, String> e) {
        logger.warn("ignoring unknown {} setting: [{}] with value [{}]; archiving", settingType, e.getKey(), e.getValue());
    }

    private void logInvalidSetting(String settingType, Map.Entry<String, String> e, IllegalArgumentException ex) {
        logger.warn(
            (org.apache.logging.log4j.util.Supplier<?>)
                () -> new ParameterizedMessage("ignoring invalid {} setting: [{}] with value [{}]; archiving",
                    settingType,
                    e.getKey(),
                    e.getValue()),
            ex);
    }

    @Override
    public void applyClusterState(final ClusterChangedEvent event) {
        // order is important, first metaState, and then shardsState
        // so dangling indices will be recorded
        //metaState.applyClusterState(event);
    }

    public interface GatewayStateRecoveredListener {
        void onSuccess(ClusterState build);

        void onFailure(String s);
    }
}
