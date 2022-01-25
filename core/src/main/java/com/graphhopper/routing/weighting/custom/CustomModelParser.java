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
package com.graphhopper.routing.weighting.custom;

import com.graphhopper.json.Statement;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.TurnCostProvider;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.Helper;
import com.graphhopper.util.JsonFeature;
import org.slf4j.LoggerFactory;

import java.util.*;

public class CustomModelParser {
    static final String IN_AREA_PREFIX = "in_";
    static final String BACKWARD_PREFIX = "backward_";

    // Without a cache the class creation takes 10-40ms which makes routingLM8 requests 20% slower on average.
    // CH requests and preparation is unaffected as cached weighting from preparation is used.
    // Use accessOrder==true to remove oldest accessed entry, not oldest inserted.
    private static final int CACHE_SIZE = Integer.getInteger("graphhopper.custom_weighting.cache_size", 1000);
    private static final Map<String, InterpretedCustomWeightingHelper.CompiledModel> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, InterpretedCustomWeightingHelper.CompiledModel>(CACHE_SIZE, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    // This internal cache ensures that the "internal" Weighting classes specified in the profiles, are never removed regardless
    // of how frequent other Weightings are created and accessed. We only need to synchronize the get and put methods alone.
    // E.g. we do not care for the race condition where two identical classes are requested and one of them is overwritten.
    // TODO perf compare with ConcurrentHashMap, but I guess, if there is a difference at all, it is not big for small maps
    private static final Map<String, InterpretedCustomWeightingHelper.CompiledModel> INTERNAL_CACHE = Collections.synchronizedMap(new HashMap<>());

    private CustomModelParser() {
        // utility class
    }

    public static CustomWeighting createWeighting(BooleanEncodedValue accessEnc, DecimalEncodedValue speedEnc, DecimalEncodedValue priorityEnc,
                                                  EncodedValueLookup lookup, TurnCostProvider turnCostProvider, CustomModel customModel) {
        if (customModel == null)
            throw new IllegalStateException("CustomModel cannot be null");
        double maxSpeed = speedEnc.getMaxOrMaxStorableDecimal();
        CustomWeighting.Parameters parameters = createWeightingParameters(customModel, lookup, speedEnc, maxSpeed, priorityEnc);
        return new CustomWeighting(accessEnc, speedEnc, turnCostProvider, parameters);
    }

    public static CustomWeighting createFastestWeighting(BooleanEncodedValue accessEnc, DecimalEncodedValue speedEnc, EncodingManager lookup) {
        CustomModel cm = new CustomModel();

        return createWeighting(accessEnc, speedEnc, null, lookup, TurnCostProvider.NO_TURN_COST_PROVIDER, cm);
    }

    /**
     * This method compiles a new subclass of CustomWeightingHelper composed of the provided CustomModel caches this
     * and returns an instance.
     *
     * @param priorityEnc can be null
     */
    public static CustomWeighting.Parameters createWeightingParameters(CustomModel customModel, EncodedValueLookup lookup,
                                                                       DecimalEncodedValue avgSpeedEnc, double globalMaxSpeed,
                                                                       DecimalEncodedValue priorityEnc) {

        double globalMaxPriority = priorityEnc == null ? 1 : priorityEnc.getMaxStorableDecimal();
        // if the same custom model is used with a different base profile we cannot use the cached version
        String key = customModel + ",speed:" + avgSpeedEnc.getName() + ",global_max_speed:" + globalMaxSpeed
                + (priorityEnc == null ? "" : "prio:" + priorityEnc.getName() + ",global_max_priority:" + globalMaxPriority);
        if (key.length() > 100_000)
            throw new IllegalArgumentException("Custom Model too big: " + key.length());

        try {
            InterpretedCustomWeightingHelper.validateModel(customModel, lookup);

            InterpretedCustomWeightingHelper.CompiledModel compiled = customModel.isInternal() ? INTERNAL_CACHE.get(key) : null;
            if (compiled == null && CACHE_SIZE > 0)
                compiled = CACHE.get(key);
            if (compiled == null) {
                compiled = InterpretedCustomWeightingHelper.compile(customModel, lookup, globalMaxSpeed, globalMaxPriority);

                if (customModel.isInternal()) {
                    INTERNAL_CACHE.put(key, compiled);
                    if (INTERNAL_CACHE.size() > 100) {
                        CACHE.putAll(INTERNAL_CACHE);
                        INTERNAL_CACHE.clear();
                        LoggerFactory.getLogger(CustomModelParser.class).warn("Internal cache must stay small but was "
                                + INTERNAL_CACHE.size() + ". Cleared it. Misuse of CustomModel::internal?");
                    }
                } else if (CACHE_SIZE > 0) {
                    CACHE.put(key, compiled);
                }
            }

            Map<String, JsonFeature> areaFeatures = CustomModel.getAreasAsMap(customModel.getAreas());
            return compiled.createParameters(lookup, avgSpeedEnc, priorityEnc, areaFeatures);
        } catch (IllegalArgumentException ex) {
            CACHE.remove(key);
            INTERNAL_CACHE.remove(key);
            String message = ex.getMessage();
            if (message != null && message.startsWith("Cannot compile expression"))
                throw ex;
            throw new IllegalArgumentException("Cannot compile expression: " + message, ex);
        } catch (RuntimeException ex) {
            CACHE.remove(key);
            INTERNAL_CACHE.remove(key);
            throw new IllegalArgumentException("Cannot compile expression: " + ex.getMessage(), ex);
        }
    }

    static void parseExpressions(StringBuilder expressions, NameValidator nameInConditionValidator,
                                 String exceptionInfo, Set<String> createObjects, List<Statement> list) {

        for (Statement statement : list) {
            // avoid parsing the RHS value expression again as we just did it to get the maximum values in createClazz
            if (statement.getKeyword() == Statement.Keyword.ELSE) {
                if (!Helper.isEmpty(statement.getCondition()))
                    throw new IllegalArgumentException("condition must be empty but was " + statement.getCondition());

                expressions.append("else {").append(statement.getOperation().build(statement.getValue())).append("; }\n");
            } else if (statement.getKeyword() == Statement.Keyword.ELSEIF || statement.getKeyword() == Statement.Keyword.IF) {
                ParseResult parseResult = ConditionalExpressionVisitor.parse(statement.getCondition(), nameInConditionValidator);
                if (!parseResult.ok)
                    throw new IllegalArgumentException(exceptionInfo + " invalid condition \"" + statement.getCondition() + "\"" +
                            (parseResult.invalidMessage == null ? "" : ": " + parseResult.invalidMessage));
                createObjects.addAll(parseResult.guessedVariables);
                if (statement.getKeyword() == Statement.Keyword.ELSEIF)
                    expressions.append("else ");
                expressions.append("if (").append(parseResult.converted).append(") {").append(statement.getOperation().build(statement.getValue())).append(";}\n");
            } else {
                throw new IllegalArgumentException("The statement must be either 'if', 'else_if' or 'else'");
            }
        }
        expressions.append("return value;\n");
    }
}
