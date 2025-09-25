/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.http;

import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.gtfs.GraphHopperGtfs;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GraphHopperManaged implements Managed {

    private final static Logger logger = LoggerFactory.getLogger(GraphHopperManaged.class);
    private final GraphHopper graphHopper;

    public GraphHopperManaged(GraphHopperConfig configuration) {
        if (configuration.has("gtfs.file")) {
            graphHopper = new GraphHopperGtfs(configuration);
        } else {
            graphHopper = new GraphHopper();
        }

        ObjectMapper yamlOM = Jackson.initObjectMapper(new ObjectMapper(new YAMLFactory()));
        ObjectMapper jsonOM = Jackson.newObjectMapper();
        List<Profile> newProfiles = new ArrayList<>();
        for (Profile profile : configuration.getProfiles()) {
            if (!CustomWeighting.NAME.equals(profile.getWeighting())) {
                newProfiles.add(profile);
                continue;
            }
            String customModelLocation = profile.getHints().getString("custom_model_file", "");
            if (customModelLocation.isEmpty())
                throw new IllegalArgumentException("Missing 'custom_model_file' field in profile '" + profile.getName() + "' if you want an empty custom model set it to 'empty'");
            if ("empty".equals(customModelLocation))
                newProfiles.add(new CustomProfile(profile).setCustomModel(new CustomModel()));
            else
                try {
                    CustomModel customModel = (customModelLocation.endsWith(".json") ? jsonOM : yamlOM).readValue(new File(customModelLocation), CustomModel.class);
                    newProfiles.add(new CustomProfile(profile).setCustomModel(customModel));
                } catch (Exception ex) {
                    throw new RuntimeException("Cannot load custom_model from " + customModelLocation + " for profile " + profile.getName(), ex);
                }
        }
        configuration.setProfiles(newProfiles);

        graphHopper.init(configuration);
    }

    @Override
    public void start() {
        graphHopper.importOrLoad();
        logger.info("loaded graph at:{}, data_reader_file:{}, encoded values:{}, {} ints for edge flags, {}",
                graphHopper.getGraphHopperLocation(), graphHopper.getOSMFile(),
                graphHopper.getEncodingManager().toEncodedValuesAsString(),
                graphHopper.getEncodingManager().getIntsForFlags(),
                graphHopper.getBaseGraph().toDetailsString());
    }

    public GraphHopper getGraphHopper() {
        return graphHopper;
    }

    @Override
    public void stop() {
        graphHopper.close();
    }


}
