package com.graphhopper.routing.weighting.custom;

import com.android.dx.*;
import com.graphhopper.json.Statement;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.custom.boolean_expression_helper.BExprPreParseException;
import com.graphhopper.routing.weighting.custom.boolean_expression_helper.BExprTree;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.JsonFeature;
import com.graphhopper.util.shapes.Polygon;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.prep.PreparedPolygon;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AndroidWeightingHelperCreator {

    public static File dexCache;
    static final String IN_AREA_PREFIX = "in_";
    private static final Set<String> allowedNames = new HashSet<>(Arrays.asList("edge", "Math"));
    private static final AtomicLong longVal = new AtomicLong(1);

    public static Class<?> createClazz(CustomModel customModel, EncodedValueLookup lookup, double globalMaxSpeed) {
        try {
            DexMaker dexMaker = new DexMaker();

            long counter = longVal.incrementAndGet();
            String classname = "AndroidWeightingHelperSubclass" + counter;
            TypeId<? extends CustomWeightingHelper> generatedClassType = TypeId.get("L" + classname + ";");
            TypeId<CustomWeightingHelper> baseClassType = TypeId.get(CustomWeightingHelper.class);
            dexMaker.declare(generatedClassType, classname + ".generated", Modifier.PUBLIC, baseClassType);

            generateConstructorsAndFields(dexMaker, generatedClassType, baseClassType, CustomWeightingHelper.class);
            List<LocalVariable> localVariables = generateLocalVariables(dexMaker, generatedClassType, lookup, customModel);
            generateInitMethod(dexMaker, generatedClassType, baseClassType, lookup, localVariables);
            generateGetPriorityMethod(dexMaker, generatedClassType, localVariables, customModel.getPriority());
            generateGetSpeedMethod(
                    dexMaker, generatedClassType, baseClassType, localVariables, customModel.getSpeed(), globalMaxSpeed
            );

            ClassLoader loader = dexMaker.generateAndLoad(CustomWeightingHelper.class.getClassLoader(), dexCache);
            return loader.loadClass(classname);
        } catch (Exception ex) {
            String errString = "Cannot compile expression";
            throw new IllegalArgumentException(errString + ": " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static <T, G extends T> void generateConstructorsAndFields(
            DexMaker dexMaker,
            TypeId<G> generatedType,
            TypeId<T> superType,
            Class<T> superClass
    ) {
        for (Constructor<T> constructor : getConstructorsToOverwrite(superClass)) {
            if (constructor.getModifiers() == Modifier.FINAL) {
                continue;
            }
            TypeId<?>[] types = classArrayToTypeArray(constructor.getParameterTypes());
            MethodId<?, ?> method = generatedType.getConstructor(types);
            Code constructorCode = dexMaker.declare(method, Modifier.PUBLIC);
            Local<G> thisRef = constructorCode.getThis(generatedType);
            Local<?>[] params = new Local[types.length];
            for (int i = 0; i < params.length; ++i) {
                params[i] = constructorCode.getParameter(i, types[i]);
            }
            MethodId<T, ?> superConstructor = superType.getConstructor(types);
            constructorCode.invokeDirect(superConstructor, null, thisRef, params);
            constructorCode.returnVoid();
        }
    }

    private static List<LocalVariable> generateLocalVariables(
            DexMaker dexMaker,
            TypeId<? extends CustomWeightingHelper> generatedType,
            EncodedValueLookup lookup,
            CustomModel customModel
    ) {
        List<LocalVariable> variables = new ArrayList<>();
        ArrayList<Statement> statements = new ArrayList<>();
        statements.addAll(customModel.getPriority());
        statements.addAll(customModel.getSpeed());
        for (String variable : getVariableNames(statements)) {
            if (lookup.hasEncodedValue(variable)) {
                EncodedValue enc = lookup.getEncodedValue(variable, EncodedValue.class);
                FieldId<? extends CustomWeightingHelper, ? extends EncodedValue> encLocal
                        = generatedType.getField(TypeId.get(getInterface(enc)), variable + "_enc");
                dexMaker.declare(encLocal, Modifier.PROTECTED, null);

                LocalVariable localVariable = new LocalVariable();
                localVariable.name = variable;
                localVariable.fieldId = encLocal;
                localVariable.isArea = false;
                variables.add(localVariable);
            } else if (variable.startsWith(IN_AREA_PREFIX)) {
                String id = variable.substring(IN_AREA_PREFIX.length());
                if (!EncodingManager.isValidEncodedValue(id))
                    throw new IllegalArgumentException("Area has invalid name: " + variable);
                JsonFeature feature = customModel.getAreas().get(id);
                if (feature == null)
                    throw new IllegalArgumentException("Area '" + id + "' wasn't found");
                if (feature.getGeometry() == null)
                    throw new IllegalArgumentException("Area '" + id + "' does not contain a geometry");
                if (!(feature.getGeometry() instanceof Polygonal))
                    throw new IllegalArgumentException("Currently only type=Polygon is supported for areas but was " + feature.getGeometry().getGeometryType());
                if (feature.getProperties() != null && !feature.getProperties().isEmpty() || feature.getBBox() != null)
                    throw new IllegalArgumentException("Bounding box and properties of area " + id + " must be empty");

                FieldId<? extends CustomWeightingHelper, Polygon> areaLocal
                        = generatedType.getField(TypeId.get(Polygon.class), variable);
                dexMaker.declare(areaLocal, Modifier.PROTECTED, null);

                LocalVariable localVariable = new LocalVariable();
                localVariable.name = variable;
                localVariable.fieldId = areaLocal;
                localVariable.isArea = true;
                variables.add(localVariable);
            } else {
                if (!isValidVariableName(variable))
                    throw new IllegalArgumentException("Variable not supported: " + variable);
            }
        }

        return variables;
    }

    private static class LocalVariable {
        String name;
        FieldId<? extends CustomWeightingHelper, ?> fieldId;
        boolean isArea;
        Class<? extends EncodedValue> type;
    }

    private static HashSet<String> getVariableNames(List<Statement> statements) {
        HashSet<String> variableNames = new HashSet<>();
        for (Statement statement : statements) {
            List<String> conditions = new ArrayList<>();

            Comparator[] comparators = Comparator.values();

            if (
                    statement.getCondition() == null ||
                            statement.getCondition().trim().equals("") ||
                            statement.getCondition().trim().equals("null")
            ) {
                return variableNames;
            }

            for (String orStatements : statement.getCondition()
                    .replaceAll("\\(|\\)", "")
                    .split("\\|\\|")
            ) {
                conditions.addAll(
                        Arrays.stream(
                                        orStatements.split("&&"))
                                .map(String::trim)
                                .collect(Collectors.toList()
                                )
                );
            }

            for (String condition : conditions) {
                Comparator conditionComparator = Arrays.stream(comparators)
                        .filter(comparator -> condition.contains(comparator.value))
                        .findFirst()
                        .orElse(null);

                if (conditionComparator != null) {
                    variableNames.add(condition.split(conditionComparator.value)[0].trim());
                } else {
                    variableNames.add(condition);
                }
            }

        }
        return variableNames;
    }

    private static boolean isValidVariableName(String name) {
        return name.startsWith(IN_AREA_PREFIX) || allowedNames.contains(name);
    }

    /**
     * @return the interface of the provided EncodedValue, e.g. IntEncodedValue (only interface) or
     * BooleanEncodedValue (first interface).
     */
    private static Class<? extends EncodedValue> getInterface(EncodedValue enc) {
        if (enc instanceof StringEncodedValue) return IntEncodedValue.class;
        if (enc.getClass().getInterfaces().length == 0) return enc.getClass();

        //noinspection unchecked
        return (Class<? extends EncodedValue>) enc.getClass().getInterfaces()[0];
    }

    // The type parameter on Constructor is the class in which the constructor is declared.
    // The getDeclaredConstructors() method gets constructors declared only in the given class,
    // hence this cast is safe.
    @SuppressWarnings("unchecked")
    private static <T> Constructor<T>[] getConstructorsToOverwrite(Class<T> clazz) {
        return (Constructor<T>[]) clazz.getDeclaredConstructors();
    }

    private static TypeId<?>[] classArrayToTypeArray(Class<?>[] input) {
        TypeId<?>[] result = new TypeId[input.length];
        for (int i = 0; i < input.length; ++i) {
            result[i] = TypeId.get(input[i]);
        }
        return result;
    }

    /**
     * Generates a method that looks like this
     * <pre>
     * {@code
     *  public void init(EncodedValueLookup lookup, DecimalEncodedValue avgSpeedEnc, Map<String, JsonFeature> areas) {
     *      this.avg_speed_enc = avgSpeedEnc;
     *      if (lookup.hasEncodedValue("bike_network"))
     *          this.bike_network_enc = (EnumEncodedValue) lookup.getEncodedValue("bike_network", EncodedValue.class);
     *      if (lookup.hasEncodedValue("road_class"))
     *          this.road_class_enc = (EnumEncodedValue) lookup.getEncodedValue("road_class", EncodedValue.class);
     *  }
     * }
     * </pre>
     */
    private static void generateInitMethod(
            DexMaker dexMaker,
            TypeId<? extends CustomWeightingHelper> generatedClassType,
            TypeId<CustomWeightingHelper> baseClassType,
            EncodedValueLookup lookup,
            List<LocalVariable> localVariables
    ) {
        String methodName = "init";

        @SuppressWarnings("unchecked")
        MethodId<?, Void> method = generatedClassType.getMethod(
                TypeId.VOID,
                methodName,
                TypeId.get(EncodedValueLookup.class),
                TypeId.get(DecimalEncodedValue.class),
                TypeId.get((Class<Map<String, JsonFeature>>) (Class<?>) Map.class)
        );
        Code code = dexMaker.declare(method, Modifier.PUBLIC);

        Local<EncodedValueLookup> lookupRef = code.getParameter(0, TypeId.get(EncodedValueLookup.class));
        Local<DecimalEncodedValue> avgSpeedEncParam = code.getParameter(1, TypeId.get(DecimalEncodedValue.class));

        @SuppressWarnings("unchecked")
        Local<Map<String, JsonFeature>> areasParam
                = code.getParameter(2, TypeId.get((Class<Map<String, JsonFeature>>) (Class<?>) Map.class));

        Local<? extends CustomWeightingHelper> thisRef = code.getThis(generatedClassType);
        FieldId<CustomWeightingHelper, DecimalEncodedValue> avgSpeedEnc
                = baseClassType.getField(TypeId.get(DecimalEncodedValue.class), "avg_speed_enc");

        TypeId<EncodedValueLookup> encodedValueLookupTypeId = TypeId.get(EncodedValueLookup.class);
        MethodId<EncodedValueLookup, Boolean> hasEncodedValueMethod
                = encodedValueLookupTypeId.getMethod(TypeId.BOOLEAN, "hasEncodedValue", TypeId.STRING);

        @SuppressWarnings("unchecked")
        TypeId<Map<String, JsonFeature>> areasTypeId = TypeId.get((Class<Map<String, JsonFeature>>) (Class<?>) Map.class);

        TypeId<JsonFeature> jsonFeatureTypeId = TypeId.get(JsonFeature.class);
        TypeId<PreparedPolygon> preparedPolygonTypeId = TypeId.get(PreparedPolygon.class);
        TypeId<Polygon> polygonTypeId = TypeId.get(Polygon.class);

        Local<Boolean> trueResult = code.newLocal(TypeId.BOOLEAN);
        @SuppressWarnings("rawtypes")
        Local<Class> classParam = code.newLocal(TypeId.get(Class.class));

        List<EncodedValueVariableContainer> encodedValueVariableContainers = new ArrayList<>();
        List<AreaVariableContainer> areaVariableContainers = new ArrayList<>();

        localVariables.forEach(localVariable -> {
            if (!localVariable.isArea) {
                EncodedValueVariableContainer container = new EncodedValueVariableContainer();
                container.name = localVariable.name;
                container.encodedName = localVariable.name + "_enc";
                //noinspection unchecked
                container.fieldId = (FieldId<? extends CustomWeightingHelper, ? extends EncodedValue>) localVariable.fieldId;

                container.lookupParam = code.newLocal(TypeId.STRING);

                container.hasEncodedValueResult = code.newLocal(TypeId.BOOLEAN);
                container.getEncodedValueResult = code.newLocal(TypeId.get(EncodedValue.class));

                container.encodedValue = lookup.getEncodedValue(localVariable.name, EncodedValue.class);
                container.getEncodedValueCastedResult = code.newLocal(TypeId.get(getInterface(container.encodedValue)));

                container.encodedValueParam = code.newLocal(TypeId.STRING);

                encodedValueVariableContainers.add(container);
            } else {
                AreaVariableContainer container = new AreaVariableContainer();

                container.name = localVariable.name;
                //noinspection unchecked
                container.fieldId = (FieldId<? extends CustomWeightingHelper, Polygon>) localVariable.fieldId;

                container.jsonFeature = code.newLocal(TypeId.get(JsonFeature.class));
                container.jsonFeatureParam = code.newLocal(TypeId.STRING);

                container.polygon = code.newLocal(TypeId.get(Polygon.class));
                container.preparedPolygon = code.newLocal(TypeId.get(PreparedPolygon.class));
                container.geometry = code.newLocal(TypeId.get(Geometry.class));
                container.geometryCasted = code.newLocal(TypeId.get(Polygonal.class));

                areaVariableContainers.add(container);
            }
        });

        // Instructions

        code.loadConstant(trueResult, true);
        code.loadDeferredClassConstant(classParam, TypeId.get(EncodedValue.class));

        code.iput(avgSpeedEnc, thisRef, avgSpeedEncParam);

        encodedValueVariableContainers.forEach((container) -> {
            code.loadConstant(container.lookupParam, container.name);
            code.loadConstant(container.encodedValueParam, container.name);

            Label conditionLabel = new Label();

            code.invokeInterface(
                    hasEncodedValueMethod,
                    container.hasEncodedValueResult,
                    lookupRef,
                    container.lookupParam
            );

            code.compare(Comparison.NE, conditionLabel, container.hasEncodedValueResult, trueResult);

            MethodId<EncodedValueLookup, ? extends EncodedValue> getEncodedValueMethod
                    = encodedValueLookupTypeId.getMethod(
                    TypeId.get(getInterface(container.encodedValue)),
                    "getEncodedValue",
                    TypeId.STRING,
                    TypeId.get(Class.class)
            );

            code.invokeInterface(
                    getEncodedValueMethod,
                    container.getEncodedValueResult,
                    lookupRef,
                    container.encodedValueParam,
                    classParam
            );

            code.cast(container.getEncodedValueCastedResult, container.getEncodedValueResult);

            //noinspection unchecked
            code.iput(
                    (FieldId<CustomWeightingHelper, EncodedValue>) container.fieldId,
                    thisRef,
                    container.getEncodedValueCastedResult
            );

            code.mark(conditionLabel);
        });

        areaVariableContainers.forEach((container) -> {
            MethodId<Map<String, JsonFeature>, JsonFeature> getJsonFeature
                    = areasTypeId.getMethod(
                    TypeId.get(JsonFeature.class),
                    "get",
                    TypeId.STRING
            );

            code.loadConstant(container.jsonFeatureParam, container.name);

            code.invokeInterface(
                    getJsonFeature,
                    container.jsonFeature,
                    areasParam,
                    container.jsonFeatureParam
            );

            MethodId<JsonFeature, Geometry> getGeometry
                    = jsonFeatureTypeId.getMethod(
                    TypeId.get(Geometry.class),
                    "getGeometry"
            );

            code.invokeDirect(
                    getGeometry,
                    container.geometry,
                    container.jsonFeature
            );

            code.cast(container.geometryCasted, container.geometry);

            MethodId<PreparedPolygon, Void> preparedPolygonConstructor
                    = preparedPolygonTypeId.getConstructor(TypeId.get(Polygonal.class));

            code.newInstance(container.preparedPolygon, preparedPolygonConstructor, container.geometryCasted);

            MethodId<Polygon, Void> polygonConstructor
                    = polygonTypeId.getConstructor(preparedPolygonTypeId);

            code.newInstance(container.polygon, polygonConstructor, container.preparedPolygon);

            //noinspection unchecked
            code.iput(
                    (FieldId<CustomWeightingHelper, Polygon>) container.fieldId,
                    thisRef,
                    container.polygon
            );
        });

        code.returnVoid();
    }

    private static class EncodedValueVariableContainer {
        String name;
        String encodedName;
        EncodedValue encodedValue;
        FieldId<? extends CustomWeightingHelper, ? extends EncodedValue> fieldId;
        Local<String> lookupParam;
        Local<Boolean> hasEncodedValueResult;
        Local<EncodedValue> getEncodedValueResult;
        Local<? extends EncodedValue> getEncodedValueCastedResult;
        Local<String> encodedValueParam;
    }

    private static class AreaVariableContainer {
        String name;
        FieldId<? extends CustomWeightingHelper, Polygon> fieldId;
        Local<JsonFeature> jsonFeature;
        Local<String> jsonFeatureParam;
        Local<Polygon> polygon;
        Local<PreparedPolygon> preparedPolygon;
        Local<Geometry> geometry;
        Local<Polygonal> geometryCasted;
    }

    /**
     * Generates a method that looks like this
     * <p>
     * public double getPriority(EdgeIteratorState edge, boolean reverse) {
     * return 1.0;
     * }
     */
    private static void generateGetPriorityMethod(
            DexMaker dexMaker,
            TypeId<? extends CustomWeightingHelper> generatedClassType,
            List<LocalVariable> localVariables,
            List<Statement> statements
    ) {
        String methodName = "getPriority";
        MethodId<?, Double> method = generatedClassType.getMethod(
                TypeId.DOUBLE,
                methodName,
                TypeId.get(EdgeIteratorState.class),
                TypeId.BOOLEAN
        );
        Code code = dexMaker.declare(method, Modifier.PUBLIC);

        Local<EdgeIteratorState> getPriorityEdge = code.getParameter(0, TypeId.get(EdgeIteratorState.class));
        Local<Boolean> getPriorityReverse = code.getParameter(1, TypeId.BOOLEAN);
        Local<Boolean> trueBoolean = code.newLocal(TypeId.BOOLEAN);

        Local<Double> result = code.newLocal(TypeId.DOUBLE);

        List<LocalStatement> localStatements = processStatements(localVariables, statements);

        Local<? extends CustomWeightingHelper> thisRef = code.getThis(generatedClassType);
        prepareLocalVariables(code, localStatements);

        code.loadConstant(result, 1.0);
        code.loadConstant(trueBoolean, true);
        //noinspection unchecked
        updateLocalVariables(code, localStatements, (Local<CustomWeightingHelper>) thisRef, getPriorityEdge, getPriorityReverse, trueBoolean);
        generateConditions(code, localStatements, result);

        code.returnValue(result);
    }

    private static List<LocalStatement> processStatements(
            List<LocalVariable> localVariables,
            List<Statement> statements
    ) {
        List<LocalStatement> localStatements = new ArrayList<>();
        for (Statement statement : statements) {

            LocalStatement localStatement = new LocalStatement();
            localStatement.value = statement.getValue();
            localStatement.keyword = statement.getKeyword();
            localStatement.operation = statement.getOperation();

            // 1st step get all conditions from the statement and replace them with IDs.
            // Save conditions separately to the list.
            List<String> conditionsStrings = new ArrayList<>();

            if (
                    statement.getCondition() == null ||
                            statement.getCondition().trim().equals("") ||
                            statement.getCondition().trim().equals("null")
            ) {
                localStatement.expressions = new ArrayList<>();
                localStatements.add(localStatement);
                continue;
            }

            String statementCondition = statement.getCondition().replaceAll(" ", "");

            for (String orStatements : statementCondition
                    .replaceAll("\\(|\\)", "")
                    .split("\\|\\|")
            ) {
                for (String c : Arrays.stream(
                                orStatements.split("&&"))
                        .map(String::trim)
                        .collect(Collectors.toList()
                        )) {
                    if (!conditionsStrings.contains(c)) {
                        conditionsStrings.add(c);
                    }
                }
            }

            for (int i = 0; i < conditionsStrings.size(); i++) {
                statementCondition = statementCondition.replaceAll(conditionsStrings.get(i), Integer.toString(i));
            }

            Comparator[] comparators = Comparator.values();

            // 2nd step process conditions and prepare parsed list
            List<Condition> conditions = conditionsStrings.stream().map((conditionString) -> {
                Condition condition = new Condition();

                Comparator conditionComparator = Arrays.stream(comparators)
                        .filter((comparator) -> conditionString.contains(comparator.value))
                        .findFirst()
                        .orElse(null);

                String localVariableName;
                if (conditionComparator != null) {
                    condition.comparator = conditionComparator;
                    setConditionValue(condition, conditionString.split(conditionComparator.value)[1].trim());
                    localVariableName = conditionString.split(conditionComparator.value)[0].trim();
                } else {
                    condition.valueType = Boolean.TYPE;
                    condition.encodedValueType = BooleanEncodedValue.class;
                    String variableName = conditionString;
                    if (variableName.startsWith("!")) {
                        condition.value = false;
                        variableName = variableName.replace("!", "");
                    } else {
                        condition.value = true;
                    }
                    localVariableName = variableName;
                }

                condition.localVariable = localVariables.stream()
                        .filter((variable) -> variable.name.equals(localVariableName))
                        .findFirst()
                        .orElseThrow(NullPointerException::new);

                return condition;
            }).collect(Collectors.toList());

            localStatement.conditions = conditions;

            // 3rd step simplify statementCondition into an array
            List<List<Integer>> expressions = new ArrayList<>();
            try {
                statementCondition = statementCondition
                        .replaceAll("\\|\\|", "|")
                        .replaceAll("&&", "&");

                String simplifiedExpression;
                if (
                        !statementCondition.contains("&") || !statementCondition.contains("|") ||
                                (!statementCondition.contains("&") && !statementCondition.contains("|"))
                ) {
                    simplifiedExpression = statementCondition
                            .replaceAll("\\|", "+")
                            .replaceAll("&", "*")
                            .replaceAll("\\(", "")
                            .replaceAll("\\)", "")
                            .replaceAll(" ", "")
                            .trim();
                } else {
                    BExprTree expressionTree = new BExprTree(statementCondition);
                    simplifiedExpression = expressionTree.getTruthTable().getSOP(expressionTree.getVars())
                            .replaceAll(" ", "")
                            .trim();
                }

                for (String orExpressions : simplifiedExpression.split("\\+")) {
                    expressions.add(
                            Arrays.stream(orExpressions.split("\\*"))
                                    .map(Integer::parseInt)
                                    .collect(Collectors.toList())
                    );
                }
            } catch (BExprPreParseException e) {
                e.printStackTrace();
            }
            localStatement.expressions = expressions;

            localStatements.add(localStatement);
        }
        return localStatements;
    }

    /**
     * According to the GH documentation value could be numeric, string or:
     * road_class: (OTHER, MOTORWAY, TRUNK, PRIMARY, SECONDARY, TRACK, STEPS, CYCLEWAY, FOOTWAY, ...)
     * road_environment: (ROAD, FERRY, BRIDGE, TUNNEL, ...)
     * road_access: (DESTINATION, DELIVERY, PRIVATE, NO, ...)
     * surface: (PAVED, DIRT, SAND, GRAVEL, ...)
     * // smoothness: (EXCELLENT, GOOD, INTERMEDIATE, ...)
     * toll: (MISSING, NO, HGV, ALL)
     */
    @SuppressWarnings("rawtypes")
    private static void setConditionValue(Condition condition, String value) {

        // Try parsing the value as String
        if (value.contains("'")) {
            // The value is string
            // Patter to find everything between ''
            Pattern pattern = Pattern.compile("(?<=^')(.*)(?='$)");
            condition.value = pattern.matcher(value).group(1);
            condition.valueType = String.class;
            condition.encodedValueType = StringEncodedValue.class;
            return;
        }

        // Try parsing the value as integer
        try {
            condition.value = Integer.parseInt(value);
            // The value is integer
            condition.valueType = Integer.TYPE;
            condition.encodedValueType = IntEncodedValue.class;
            return;
        } catch (NumberFormatException exception) {
            // The value is not integer
        }

        // Try parsing the value as double
        try {
            condition.value = Double.parseDouble(value);
            // The value is double
            condition.valueType = Double.TYPE;
            condition.encodedValueType = DecimalEncodedValue.class;
            return;
        } catch (NumberFormatException exception) {
            // The value is not double
        }

        // Try parsing the value as possible enums
        Class[] possibleClasses = new Class[]{
                RoadClass.class, RoadEnvironment.class, RoadAccess.class, RouteNetwork.class, Surface.class, Toll.class
        };

        for (Class possibleClass : possibleClasses) {
            //noinspection unchecked
            if (setConditionEnumValue(possibleClass, condition, value)) {
                break;
            }
        }

        if (condition.value == null && condition.valueType == null) {
            throw new IllegalArgumentException("Cannot parse condition value " + value);
        }
    }

    private static <T extends Enum<T>> boolean setConditionEnumValue(
            Class<T> clazz,
            Condition condition,
            String value
    ) {
        try {
            T.valueOf(clazz, value);
            condition.value = value;
            condition.valueType = clazz;
            condition.encodedValueType = EnumEncodedValue.class;
            return true;
        } catch (IllegalArgumentException exception) {
            // Value is not this enum
            return false;
        }
    }

    private static class LocalStatement {
        Statement.Op operation;
        Statement.Keyword keyword;
        double value;
        Local<Double> operationValue;

        List<Condition> conditions;

        // Expressions list represents an "or" list of groups of "and expressions"
        // For example: (A && B && C) || (D && E && F)
        // The integer number is a position of the condition from onditions
        List<List<Integer>> expressions;

        Label label = new Label();
    }

    @SuppressWarnings("rawtypes")
    private static class Condition<T> {
        LocalVariable localVariable;
        Local<T> leftEncodedLocal;
        Local<T> leftLocal;
        Local<T> rightLocal;
        Local<Integer> doubleComparisonResult;
        Local<Integer> doubleComparisonAnchor;
        Local<String> rightLocalHelper;
        Comparator comparator;
        T value;
        Class<T> valueType;
        Class<T> encodedValueType;
        Map<Integer, Label> labels = new HashMap<>();
    }

    private enum Comparator {

        EQUALS("=="),
        NOT_EQUALS("!="),
        BIGGER(">"),
        BIGGER_OR_EQUALS(">="),
        SMALLER("<"),
        SMALLER_OR_EQUALS("<=");

        String value;

        Comparator(String value) {
            this.value = value;
        }

        public Comparison getComparison() {
            switch (this) {
                case EQUALS:
                    return Comparison.EQ;
                case NOT_EQUALS:
                    return Comparison.NE;
                case BIGGER:
                    return Comparison.GT;
                case BIGGER_OR_EQUALS:
                    return Comparison.GE;
                case SMALLER:
                    return Comparison.LT;
                case SMALLER_OR_EQUALS:
                    return Comparison.LE;
            }
            throw new IllegalArgumentException();
        }
    }

    /**
     * Generates a method that looks like this
     * <p>
     * public double getSpeed(EdgeIteratorState edge, boolean reverse) {
     * return getRawSpeed(edge, reverse);
     * }
     */
    private static void generateGetSpeedMethod(
            DexMaker dexMaker,
            TypeId<? extends CustomWeightingHelper> generatedClassType,
            TypeId<CustomWeightingHelper> baseClassType,
            List<LocalVariable> localVariables,
            List<Statement> statements,
            double globalMaxSpeed
    ) {
        String methodName = "getSpeed";
        MethodId<?, Double> method = generatedClassType.getMethod(TypeId.DOUBLE,
                methodName,
                TypeId.get(EdgeIteratorState.class),
                TypeId.BOOLEAN);
        Code code = dexMaker.declare(method, Modifier.PUBLIC);
        Local<EdgeIteratorState> getSpeedEdge = code.getParameter(0, TypeId.get(EdgeIteratorState.class));
        Local<Boolean> getSpeedReverse = code.getParameter(1, TypeId.BOOLEAN);
        Local<Boolean> trueBoolean = code.newLocal(TypeId.BOOLEAN);

        Local<Double> globalMaxSpeedValue = code.newLocal(TypeId.DOUBLE);
        Local<Double> result = code.newLocal(TypeId.DOUBLE);

        List<LocalStatement> localStatements = processStatements(localVariables, statements);
        prepareLocalVariables(code, localStatements);

        String getRawSpeedMethodName = "getRawSpeed";
        MethodId<CustomWeightingHelper, Double> getRawSpeedMethod
                = baseClassType.getMethod(TypeId.DOUBLE, getRawSpeedMethodName, TypeId.get(EdgeIteratorState.class), TypeId.BOOLEAN);
        Local<? extends CustomWeightingHelper> thisRef = code.getThis(generatedClassType);
        code.invokeSuper(getRawSpeedMethod, result, thisRef, getSpeedEdge, getSpeedReverse);

        code.loadConstant(trueBoolean, true);
        //noinspection unchecked
        updateLocalVariables(code, localStatements, (Local<CustomWeightingHelper>) thisRef, getSpeedEdge, getSpeedReverse, trueBoolean);
        generateConditions(code, localStatements, result);

        code.loadConstant(globalMaxSpeedValue, globalMaxSpeed);

        MethodId<Math, Double> minMethod =
                TypeId.get(Math.class).getMethod(TypeId.DOUBLE, "min", TypeId.DOUBLE, TypeId.DOUBLE);
        code.invokeStatic(minMethod, result, result, globalMaxSpeedValue);

        code.returnValue(result);
    }

    @SuppressWarnings("unchecked")
    private static void prepareLocalVariables(Code code, List<LocalStatement> statements) {
        if (statements.isEmpty()) return;

        statements.forEach(statement -> {
            statement.operationValue = code.newLocal(TypeId.DOUBLE);
            if (statement.conditions != null && !statement.conditions.isEmpty()) {
                statement.conditions.forEach(condition -> {
                    condition.leftLocal = code.newLocal(TypeId.get(condition.valueType));
                    condition.leftEncodedLocal = code.newLocal(TypeId.get(condition.encodedValueType));
                    condition.rightLocal = code.newLocal(TypeId.get(condition.valueType));
                    condition.rightLocalHelper = code.newLocal(TypeId.STRING);

                    condition.doubleComparisonResult = code.newLocal(TypeId.INT);
                    condition.doubleComparisonAnchor = code.newLocal(TypeId.INT);
                });
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void updateLocalVariables(
            Code code,
            List<LocalStatement> statements,
            Local<CustomWeightingHelper> thisRef,
            Local<EdgeIteratorState> edge,
            Local<Boolean> reverse,
            Local<Boolean> trueBoolean
    ) {
        TypeId<EdgeIteratorState> edgeTypeId = TypeId.get(EdgeIteratorState.class);

        if (statements.isEmpty()) return;
        statements.forEach(statement -> {
            code.loadConstant(statement.operationValue, statement.value);

            if (statement.conditions != null && !statement.conditions.isEmpty()) {
                statement.conditions.forEach(condition -> {

                    code.iget(
                            (FieldId<CustomWeightingHelper, ?>) condition.localVariable.fieldId,
                            condition.leftEncodedLocal,
                            thisRef
                    );

                    TypeId<?> leftLocalReturnTypeId;
                    if (condition.encodedValueType == DecimalEncodedValue.class) {
                        leftLocalReturnTypeId = TypeId.DOUBLE;
                    } else if (condition.encodedValueType == IntEncodedValue.class) {
                        leftLocalReturnTypeId = TypeId.INT;
                    } else if (condition.encodedValueType == StringEncodedValue.class) {
                        leftLocalReturnTypeId = TypeId.STRING;
                    } else if (condition.encodedValueType == BooleanEncodedValue.class) {
                        leftLocalReturnTypeId = TypeId.BOOLEAN;
                    } else if (condition.encodedValueType == EnumEncodedValue.class) {
                        leftLocalReturnTypeId = TypeId.get(Enum.class);
                    } else {
                        leftLocalReturnTypeId = TypeId.get(condition.valueType);
                    }

                    Label trueLabel = new Label();
                    Label falseLabel = new Label();
                    code.compare(Comparison.EQ, trueLabel, reverse, trueBoolean);

                    MethodId<EdgeIteratorState, ?> edgeGetMethod = edgeTypeId.getMethod(
                            leftLocalReturnTypeId,
                            "get",
                            TypeId.get(condition.encodedValueType)
                    );
                    code.invokeInterface(
                            edgeGetMethod,
                            condition.leftLocal,
                            edge,
                            condition.leftEncodedLocal
                    );

                    code.jump(falseLabel);

                    code.mark(trueLabel);

                    MethodId<EdgeIteratorState, ?> edgeGetReverseMethod = edgeTypeId.getMethod(
                            leftLocalReturnTypeId,
                            "getReverse",
                            TypeId.get(condition.encodedValueType));

                    code.invokeInterface(
                            edgeGetReverseMethod,
                            condition.leftLocal,
                            edge,
                            condition.leftEncodedLocal
                    );

                    code.mark(falseLabel);

                    if (condition.valueType.isEnum()) {
                        String valueOfMethodName = "valueOf";
                        TypeId<? extends Enum<?>> enumType = TypeId.get(condition.valueType);
                        MethodId<? extends Enum<?>, ? extends Enum<?>> valueOfMethod = enumType.getMethod(
                                enumType,
                                valueOfMethodName,
                                TypeId.STRING
                        );
                        code.loadConstant(condition.rightLocalHelper, (String) condition.value);
                        code.invokeStatic(valueOfMethod, condition.rightLocal, condition.rightLocalHelper);
                    } else if (condition.valueType == Double.TYPE) {
                        code.loadConstant(condition.rightLocal, (double) condition.value);
                    } else if (condition.valueType == Integer.TYPE) {
                        code.loadConstant(condition.rightLocal, (int) condition.value);
                    } else if (condition.valueType == Boolean.TYPE) {
                        code.loadConstant(condition.rightLocal, (boolean) condition.value);
                    } else if (!condition.valueType.isPrimitive()) {
                        code.loadConstant(condition.rightLocal, condition.valueType.cast(condition.value));
                    } else {
                        code.loadConstant(condition.rightLocal, condition.value);
                    }
                });
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void generateConditions(
            Code code,
            List<LocalStatement> statements,
            Local<Double> result
    ) {
        Label exitLabel = new Label();
        for (int s = 0; s < statements.size(); s++) {
            LocalStatement statement = statements.get(s);
            code.mark(statement.label);
            if (statement.keyword == Statement.Keyword.IF || statement.keyword == Statement.Keyword.ELSEIF) {
                Label operationLabel = new Label();
                Label skipLabel = new Label();
                List<Label> orLabels = statement.expressions.stream().map(v -> new Label()).collect(Collectors.toList());
                for (int i = 0; i < statement.expressions.size(); i++) {
                    code.mark(orLabels.get(i));
                    for (int j = 0; j < statement.expressions.get(i).size(); j++) {
                        Condition condition = statement.conditions.get(statement.expressions.get(i).get(j));
                        if (!condition.labels.containsKey(i)) {
                            condition.labels.put(i, new Label());
                        }
                        code.mark((Label) condition.labels.get(i));

                        Label trueLabel;
                        if (j >= statement.expressions.get(i).size() - 1) {
                            trueLabel = operationLabel;
                        } else {
                            if (!statement.conditions.get(statement.expressions.get(i).get(j + 1)).labels.containsKey(i)) {
                                statement.conditions.get(statement.expressions.get(i).get(j + 1)).labels.put(i, new Label());
                            }
                            trueLabel = (Label) statement.conditions.get(statement.expressions.get(i).get(j + 1)).labels.get(i);
                        }
                        Label falseLabel;
                        if (i >= statement.expressions.size() - 1) {
                            falseLabel = skipLabel;
                        } else {
                            falseLabel = orLabels.get(i + 1);
                        }

                        if (condition.valueType == Double.TYPE) {
                            code.compareFloatingPoint(
                                    condition.doubleComparisonResult,
                                    condition.leftLocal,
                                    condition.rightLocal,
                                    1
                            );

                            code.loadConstant(condition.doubleComparisonAnchor, 0);

                            code.compare(
                                    condition.comparator.getComparison(),
                                    trueLabel,
                                    condition.doubleComparisonResult,
                                    condition.doubleComparisonAnchor
                            );
                        } else {
                            code.compare(
                                    condition.comparator.getComparison(),
                                    trueLabel,
                                    condition.leftLocal,
                                    condition.rightLocal
                            );
                        }
                        code.jump(falseLabel);
                    }
                }

                code.mark(skipLabel);

                LocalStatement nextElseIfStatement = getNextStatement(statements, Statement.Keyword.ELSEIF, s);
                LocalStatement nextElseStatement = getNextStatement(statements, Statement.Keyword.ELSE, s);
                LocalStatement nextIfStatement = getNextStatement(statements, Statement.Keyword.IF, s);
                if (nextElseIfStatement != null) {
                    code.jump(nextElseIfStatement.label);
                } else if (nextElseStatement != null) {
                    code.jump(nextElseStatement.label);
                } else if (nextIfStatement != null) {
                    code.jump(nextIfStatement.label);
                } else {
                    code.jump(exitLabel);
                }

                code.mark(operationLabel);
                executeOperation(code, statement, result);

                if (nextIfStatement != null) {
                    code.jump(nextIfStatement.label);
                } else {
                    code.jump(exitLabel);
                }
            } else {
                executeOperation(code, statement, result);

                LocalStatement nextIfStatement = getNextStatement(statements, Statement.Keyword.IF, s);
                if (nextIfStatement != null) {
                    code.jump(nextIfStatement.label);
                } else {
                    code.jump(exitLabel);
                }
            }
        }
        code.mark(exitLabel);
    }

    private static LocalStatement getNextStatement(
            List<LocalStatement> statements,
            Statement.Keyword keyword,
            int currentIndex
    ) {
        if (currentIndex >= statements.size() - 1) {
            return null;
        }

        for (int i = currentIndex + 1; i < statements.size(); i++) {
            if (statements.get(i).keyword == keyword) {
                return statements.get(i);
            }
        }

        return null;
    }

    private static void executeOperation(Code code, LocalStatement statement, Local<Double> result) {
        if (statement.operation == Statement.Op.LIMIT) {
            MethodId<Math, Double> minMethod =
                    TypeId.get(Math.class).getMethod(TypeId.DOUBLE, "min", TypeId.DOUBLE, TypeId.DOUBLE);
            code.invokeStatic(minMethod, result, result, statement.operationValue);
        } else {
            code.op(BinaryOp.MULTIPLY, result, result, statement.operationValue);
        }
    }
}
