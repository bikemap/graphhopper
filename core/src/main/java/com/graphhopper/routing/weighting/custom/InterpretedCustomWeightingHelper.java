package com.graphhopper.routing.weighting.custom;

import com.graphhopper.json.MinMax;
import com.graphhopper.json.Statement;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EncodedValue;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.StringEncodedValue;
import com.graphhopper.routing.weighting.custom.CustomWeighting.EdgeToDoubleMapping;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.Helper;
import com.graphhopper.util.JsonFeature;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.shapes.Polygon;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.Java;
import org.codehaus.janino.Parser;
import org.codehaus.janino.Scanner;
import org.codehaus.janino.TokenType;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.prep.PreparedPolygon;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.graphhopper.routing.weighting.custom.CustomModelParser.BACKWARD_PREFIX;
import static com.graphhopper.routing.weighting.custom.CustomModelParser.IN_AREA_PREFIX;

final class InterpretedCustomWeightingHelper extends CustomWeightingHelper {

    static CustomWeighting.Parameters createParameters(CustomModel customModel, EncodedValueLookup lookup,
                                                        DecimalEncodedValue avgSpeedEnc, double globalMaxSpeed,
                                                        DecimalEncodedValue priorityEnc) {
        double globalMaxPriority = priorityEnc == null ? 1 : priorityEnc.getMaxStorableDecimal();

        validateValueExpressions(customModel.getPriority(), lookup);
        compileRuleBlocks(customModel.getPriority(), "priority entry", lookup, new LinkedHashSet<>());

        Set<String> priorityVariables = new LinkedHashSet<>();
        MinMax minMaxPriority = new MinMax(1, globalMaxPriority);
        FindMinMax.findMinMax(priorityVariables, minMaxPriority, customModel.getPriority(), lookup);
        if (minMaxPriority.min < 0)
            throw new IllegalArgumentException("priority has to be >=0 but can be negative (" + minMaxPriority.min + ")");
        if (minMaxPriority.max < 0)
            throw new IllegalArgumentException("maximum priority has to be >=0 but was " + minMaxPriority.max);
        List<RuleBlock> priorityBlocks = compileRuleBlocks(customModel.getPriority(), "priority entry", lookup, priorityVariables);

        validateValueExpressions(customModel.getSpeed(), lookup);
        compileRuleBlocks(customModel.getSpeed(), "speed entry", lookup, new LinkedHashSet<>());

        Set<String> speedVariables = new LinkedHashSet<>();
        MinMax minMaxSpeed = new MinMax(1, globalMaxSpeed);
        FindMinMax.findMinMax(speedVariables, minMaxSpeed, customModel.getSpeed(), lookup);
        if (minMaxSpeed.min < 0)
            throw new IllegalArgumentException("speed has to be >=0 but can be negative (" + minMaxSpeed.min + ")");
        if (minMaxSpeed.max <= 0)
            throw new IllegalArgumentException("maximum speed has to be >0 but was " + minMaxSpeed.max);
        List<RuleBlock> speedBlocks = compileRuleBlocks(customModel.getSpeed(), "speed entry", lookup, speedVariables);

        Set<String> allVariables = new LinkedHashSet<>();
        allVariables.addAll(priorityVariables);
        allVariables.addAll(speedVariables);

        Map<String, JsonFeature> areaFeatures = CustomModel.getAreasAsMap(customModel.getAreas());
        BuildResult buildResult = buildVariableAccessors(allVariables, lookup, areaFeatures);
        InterpretedCustomWeightingHelper helper = new InterpretedCustomWeightingHelper(
                speedBlocks,
                priorityBlocks,
                buildResult.variableAccessors,
                buildResult.enumLookups,
                buildResult.areaPolygons,
                minMaxSpeed.max,
                minMaxPriority.max
        );
        helper.init(lookup, avgSpeedEnc, priorityEnc, areaFeatures);

        return new CustomWeighting.Parameters(helper::getSpeed, helper::getPriority, helper.getMaxSpeed(), helper.getMaxPriority(),
                customModel.getDistanceInfluence() == null ? 0 : customModel.getDistanceInfluence(),
                customModel.getHeadingPenalty() == null ? Parameters.Routing.DEFAULT_HEADING_PENALTY : customModel.getHeadingPenalty());
    }

    private final List<RuleBlock> speedBlocks;
    private final List<RuleBlock> priorityBlocks;
    private final Map<String, VariableAccessor> variableAccessors;
    private final Map<String, EnumLookup> enumLookups;
    private final Map<String, Polygon> areaPolygons;
    private final double maxSpeed;
    private final double maxPriority;

    private InterpretedCustomWeightingHelper(List<RuleBlock> speedBlocks,
                                             List<RuleBlock> priorityBlocks,
                                             Map<String, VariableAccessor> variableAccessors,
                                             Map<String, EnumLookup> enumLookups,
                                             Map<String, Polygon> areaPolygons,
                                             double maxSpeed,
                                             double maxPriority) {
        this.speedBlocks = speedBlocks;
        this.priorityBlocks = priorityBlocks;
        this.variableAccessors = variableAccessors;
        this.enumLookups = enumLookups;
        this.areaPolygons = areaPolygons;
        this.maxSpeed = maxSpeed;
        this.maxPriority = maxPriority;
    }

    @Override
    public void init(EncodedValueLookup lookup, DecimalEncodedValue avgSpeedEnc, DecimalEncodedValue priorityEnc, Map<String, JsonFeature> areas) {
        super.init(lookup, avgSpeedEnc, priorityEnc, areas);
    }

    @Override
    public double getPriority(EdgeIteratorState edge, boolean reverse) {
        double value = getRawPriority(edge, reverse);
        if (priorityBlocks.isEmpty())
            return value;
        EvaluationContext ctx = new EvaluationContext(this, edge, reverse);
        for (RuleBlock block : priorityBlocks) {
            value = block.apply(value, ctx);
        }
        return value;
    }

    @Override
    public double getSpeed(EdgeIteratorState edge, boolean reverse) {
        double value = getRawSpeed(edge, reverse);
        if (speedBlocks.isEmpty())
            return value;
        EvaluationContext ctx = new EvaluationContext(this, edge, reverse);
        for (RuleBlock block : speedBlocks) {
            value = block.apply(value, ctx);
        }
        return value;
    }

    @Override
    protected double getMaxSpeed() {
        return maxSpeed;
    }

    @Override
    protected double getMaxPriority() {
        return maxPriority;
    }

    Polygon resolveAreaByName(String identifier) {
        Polygon polygon = areaPolygons.get(identifier);
        if (polygon == null)
            throw new IllegalArgumentException("Area '" + identifier + "' wasn't found");
        return polygon;
    }

    VariableAccessor resolveVariableAccessor(String name) {
        return variableAccessors.get(name);
    }

    EnumLookup resolveEnumLookup(String type) {
        return enumLookups.get(type);
    }

    private static List<RuleBlock> compileRuleBlocks(List<Statement> statements, String info,
                                                     EncodedValueLookup lookup, Set<String> variables) {
        if (statements.isEmpty())
            return Collections.emptyList();
        List<RuleBlock> blocks = new ArrayList<>();
        RuleBlock current = null;
        NameValidator nameValidator = name -> lookup.hasEncodedValue(name)
                || name.toUpperCase(Locale.ROOT).equals(name)
                || name.startsWith(IN_AREA_PREFIX)
                || name.startsWith(BACKWARD_PREFIX) && lookup.hasEncodedValue(name.substring(BACKWARD_PREFIX.length()));
        for (Statement statement : statements) {
            switch (statement.getKeyword()) {
                case IF:
                    current = new RuleBlock();
                    blocks.add(current);
                    current.addRule(compileRule(statement, info, lookup, variables, nameValidator, true));
                    break;
                case ELSEIF:
                    if (current == null)
                        throw new IllegalArgumentException("Every block must start with an if-statement");
                    current.addRule(compileRule(statement, info, lookup, variables, nameValidator, true));
                    break;
                case ELSE:
                    if (current == null)
                        throw new IllegalArgumentException("Every block must start with an if-statement");
                    current.addRule(compileRule(statement, info, lookup, variables, nameValidator, false));
                    current = null;
                    break;
                default:
                    throw new IllegalArgumentException("The statement must be either 'if', 'else_if' or 'else'");
            }
        }
        return blocks;
    }

    private static CompiledRule compileRule(Statement statement, String info, EncodedValueLookup lookup,
                                            Set<String> variables, NameValidator nameValidator, boolean expectCondition) {
        Java.Rvalue conditionAst = null;
        String conditionSource = null;
        if (expectCondition) {
            ParseResult parseResult = ConditionalExpressionVisitor.parse(statement.getCondition(), nameValidator);
            if (!parseResult.ok)
                throw new IllegalArgumentException(info + " invalid condition \"" + statement.getCondition() + "\""
                        + (parseResult.invalidMessage == null ? "" : ": " + parseResult.invalidMessage));
            variables.addAll(parseResult.guessedVariables);
            conditionSource = parseResult.converted == null ? statement.getCondition() : parseResult.converted.toString();
            conditionAst = parseBooleanExpression(conditionSource);
        } else if (!Helper.isEmpty(statement.getCondition())) {
            throw new IllegalArgumentException("condition must be empty but was " + statement.getCondition());
        }

        Java.Rvalue valueAst = compileValueExpression(statement.getValue(), lookup, variables, info);
        return new CompiledRule(conditionSource, conditionAst, statement.getOperation(), statement.getValue(), valueAst);
    }

    private static Java.Rvalue compileValueExpression(String expression, EncodedValueLookup lookup,
                                                       Set<String> variables, String info) {
        ParseResult parseResult = ValueExpressionVisitor.parse(expression, lookup::hasEncodedValue);
        if (!parseResult.ok)
            throw new IllegalArgumentException("Cannot compile expression: " +
                    (parseResult.invalidMessage == null ? expression + " invalid" : parseResult.invalidMessage));
        String trimmed = expression.trim();
        if (isSimpleIdentifier(trimmed) && !lookup.hasEncodedValue(trimmed) && !trimmed.isEmpty()) {
            throw new IllegalArgumentException("Cannot compile expression: '" + trimmed + "' not available");
        }
        if (parseResult.guessedVariables != null) {
            for (String variable : parseResult.guessedVariables) {
                EncodedValue enc = lookup.getEncodedValue(variable, EncodedValue.class);
                if (enc instanceof EnumEncodedValue) {
                    EnumEncodedValue<?> enumEnc = (EnumEncodedValue<?>) enc;
                    String typeName = enumEnc.getValues().getClass().getComponentType().getName();
                    throw new IllegalArgumentException("Binary numeric promotion not possible on types \"double\" and \"" + typeName + "\"");
                }
                if (!(enc instanceof DecimalEncodedValue) && !(enc instanceof IntEncodedValue)) {
                    throw new IllegalArgumentException("Binary numeric promotion not possible on types \"double\" and \"" + enc.getClass().getName() + "\"");
                }
            }
            variables.addAll(parseResult.guessedVariables);
        }
        return parseExpression(expression);
    }

    private static Java.Rvalue parseBooleanExpression(String expression) {
        return parseExpression(expression);
    }

    private static Java.Rvalue parseExpression(String expression) {
        try {
            Parser parser = new Parser(new Scanner("custom-model", new StringReader(expression)));
            Java.Rvalue result = parser.parseExpression();
            if (parser.peek().type != TokenType.END_OF_INPUT)
                throw new IllegalArgumentException("Unexpected token in expression");
            return result;
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    private static BuildResult buildVariableAccessors(Set<String> variables, EncodedValueLookup lookup,
                                                      Map<String, JsonFeature> areaFeatures) {
        Map<String, VariableAccessor> accessors = new HashMap<>();
        Map<String, EnumLookup> enumLookups = new HashMap<>();
        Map<String, Polygon> polygons = new HashMap<>();
        Map<String, EncodedValue> encodedValuesByVariable = new HashMap<>();
        for (String variable : variables) {
            if (variable.startsWith(IN_AREA_PREFIX)) {
                if (!JsonFeature.isValidId(variable))
                    throw new IllegalArgumentException("Area has invalid name: " + variable);
                String id = variable.substring(IN_AREA_PREFIX.length());
                JsonFeature feature = areaFeatures.get(id);
                if (feature == null)
                    throw new IllegalArgumentException("Area '" + id + "' wasn't found");
                if (feature.getGeometry() == null)
                    throw new IllegalArgumentException("Area '" + id + "' does not contain a geometry");
                if (!(feature.getGeometry() instanceof Polygonal))
                    throw new IllegalArgumentException("Currently only type=Polygon is supported for areas but was "
                            + feature.getGeometry().getGeometryType());
                if (feature.getBBox() != null)
                    throw new IllegalArgumentException("Bounding box of area " + id + " must be empty");
                polygons.put(variable, new Polygon(new PreparedPolygon((Polygonal) feature.getGeometry())));
                continue;
            }

            boolean inverted = false;
            String baseName = variable;
            if (variable.startsWith(BACKWARD_PREFIX)) {
                inverted = true;
                baseName = variable.substring(BACKWARD_PREFIX.length());
            }
            if (!lookup.hasEncodedValue(baseName))
                throw new IllegalArgumentException("Variable not supported: " + variable);
            EncodedValue enc = lookup.getEncodedValue(baseName, EncodedValue.class);
            encodedValuesByVariable.put(variable, enc);
            accessors.put(variable, createAccessor(enc, inverted, enumLookups));
        }
        encodedValuesByVariable.forEach((name, enc) -> {
            if (enc instanceof EnumEncodedValue) {
                EnumEncodedValue<?> enumEnc = (EnumEncodedValue<?>) enc;
                EnumLookup lookupEntry = enumLookups.computeIfAbsent(enumEnc.getEnumSimpleName(), key -> new EnumLookup(enumEnc.getValues()));
                boolean inverted = name.startsWith(BACKWARD_PREFIX);
                if (inverted) {
                    String base = name.substring(BACKWARD_PREFIX.length());
                    VariableAccessor accessor = accessors.get(name);
                    if (!(accessor instanceof EnumAccessor))
                        accessors.put(name, new EnumAccessor(enumEnc, true, lookupEntry));
                    accessor = accessors.get(base);
                    if (accessor instanceof IntAccessor)
                        accessors.put(base, new EnumAccessor(enumEnc, false, lookupEntry));
                } else {
                    VariableAccessor accessor = accessors.get(name);
                    if (!(accessor instanceof EnumAccessor))
                        accessors.put(name, new EnumAccessor(enumEnc, false, lookupEntry));
                    String backwardName = BACKWARD_PREFIX + name;
                    VariableAccessor backwardAccessor = accessors.get(backwardName);
                    if (backwardAccessor instanceof IntAccessor)
                        accessors.put(backwardName, new EnumAccessor(enumEnc, true, lookupEntry));
                }
            }
        });
        return new BuildResult(accessors, enumLookups, polygons);
    }

    static void validateModel(CustomModel customModel, EncodedValueLookup lookup) {
        validateValueExpressions(customModel.getPriority(), lookup);
        validateValueExpressions(customModel.getSpeed(), lookup);
    }

    private static void validateValueExpressions(List<Statement> statements, EncodedValueLookup lookup) {
        for (Statement statement : statements) {
            String value = statement.getValue();
            if (Helper.isEmpty(value))
                continue;
            String trimmed = value.trim();
            if (trimmed.isEmpty())
                continue;
            boolean simpleIdentifier = isSimpleIdentifier(trimmed);
            boolean knownIdentifier = lookup.hasEncodedValue(trimmed);
            ParseResult parsed = ValueExpressionVisitor.parse(trimmed, lookup::hasEncodedValue);
            if (simpleIdentifier && !knownIdentifier) {
                throw new IllegalArgumentException("Cannot compile expression: '" + trimmed + "' not available");
            }
            if (!parsed.ok)
                throw new IllegalArgumentException("Cannot compile expression: " + (parsed.invalidMessage == null ? trimmed + " invalid" : parsed.invalidMessage));
        }
    }

    private static boolean isSimpleIdentifier(String expression) {
        if (Helper.isEmpty(expression))
            return false;
        char first = expression.charAt(0);
        if (!Character.isJavaIdentifierStart(first))
            return false;
        for (int i = 1; i < expression.length(); i++) {
            if (!Character.isJavaIdentifierPart(expression.charAt(i)))
                return false;
        }
        return true;
    }

    private static VariableAccessor createAccessor(EncodedValue enc, boolean inverted,
                                                   Map<String, EnumLookup> enumLookups) {
        if (enc instanceof DecimalEncodedValue)
            return new DecimalAccessor((DecimalEncodedValue) enc, inverted);
        if (enc instanceof BooleanEncodedValue)
            return new BooleanAccessor((BooleanEncodedValue) enc, inverted);
        if (enc instanceof IntEncodedValue)
            return new IntAccessor((IntEncodedValue) enc, inverted);
        if (enc instanceof EnumEncodedValue) {
            EnumEncodedValue<?> enumEnc = (EnumEncodedValue<?>) enc;
                EnumLookup lookup = enumLookups.computeIfAbsent(enumEnc.getEnumSimpleName(), key -> new EnumLookup(enumEnc.getValues()));
            return new EnumAccessor(enumEnc, inverted, lookup);
        }
        if (enc instanceof StringEncodedValue)
            return new StringAccessor((StringEncodedValue) enc, inverted);
        throw new IllegalArgumentException("Unsupported EncodedValue: " + enc.getClass());
    }

    private static final class BuildResult {
        final Map<String, VariableAccessor> variableAccessors;
        final Map<String, EnumLookup> enumLookups;
        final Map<String, Polygon> areaPolygons;

        BuildResult(Map<String, VariableAccessor> variableAccessors, Map<String, EnumLookup> enumLookups,
                    Map<String, Polygon> areaPolygons) {
            this.variableAccessors = variableAccessors;
            this.enumLookups = enumLookups;
            this.areaPolygons = areaPolygons;
        }
    }

    private static final class EvaluationContext {
        final InterpretedCustomWeightingHelper helper;
        final EdgeIteratorState edge;
        final boolean reverse;

        EvaluationContext(InterpretedCustomWeightingHelper helper, EdgeIteratorState edge, boolean reverse) {
            this.helper = helper;
            this.edge = edge;
            this.reverse = reverse;
        }
    }

    private static final class RuleBlock {
        private final List<CompiledRule> rules = new ArrayList<>();

        void addRule(CompiledRule rule) {
            rules.add(rule);
        }

        double apply(double value, EvaluationContext ctx) {
            for (CompiledRule rule : rules) {
                if (rule.matches(ctx))
                    return rule.apply(value, ctx);
            }
            return value;
        }
    }

    private static final class CompiledRule {
        private final String conditionSource;
        private final Java.Rvalue condition;
        private final Statement.Op operation;
        private final String valueSource;
        private final Java.Rvalue valueExpression;

        CompiledRule(String conditionSource, Java.Rvalue condition, Statement.Op operation,
                     String valueSource, Java.Rvalue valueExpression) {
            this.conditionSource = conditionSource;
            this.condition = condition;
            this.operation = operation;
            this.valueSource = valueSource;
            this.valueExpression = valueExpression;
        }

        boolean matches(EvaluationContext ctx) {
            if (condition == null)
                return true;
            return ExpressionEvaluator.evaluateBoolean(condition, ctx);
        }

        double apply(double currentValue, EvaluationContext ctx) {
            double operand = ExpressionEvaluator.evaluateDouble(valueExpression, ctx, valueSource);
            if (operation == Statement.Op.MULTIPLY)
                return currentValue * operand;
            if (operation == Statement.Op.LIMIT)
                return Math.min(currentValue, operand);
            throw new IllegalArgumentException("Unsupported operation: " + operation);
        }

        @Override
        public String toString() {
            return "Rule{" +
                    "condition='" + conditionSource + '\'' +
                    ", operation=" + operation +
                    ", value='" + valueSource + '\'' +
                    '}';
        }
    }

    private abstract static class VariableAccessor {
        private final boolean inverted;

        VariableAccessor(boolean inverted) {
            this.inverted = inverted;
        }

        final boolean useReverse(EvaluationContext ctx) {
            return ctx.reverse ^ inverted;
        }

        abstract Object get(EvaluationContext ctx);
    }

    private static final class DecimalAccessor extends VariableAccessor {
        private final DecimalEncodedValue enc;

        DecimalAccessor(DecimalEncodedValue enc, boolean inverted) {
            super(inverted);
            this.enc = enc;
        }

        @Override
        Object get(EvaluationContext ctx) {
            return useReverse(ctx) ? ctx.edge.getReverse(enc) : ctx.edge.get(enc);
        }
    }

    private static final class BooleanAccessor extends VariableAccessor {
        private final BooleanEncodedValue enc;

        BooleanAccessor(BooleanEncodedValue enc, boolean inverted) {
            super(inverted);
            this.enc = enc;
        }

        @Override
        Object get(EvaluationContext ctx) {
            return useReverse(ctx) ? ctx.edge.getReverse(enc) : ctx.edge.get(enc);
        }
    }

    private static final class IntAccessor extends VariableAccessor {
        private final IntEncodedValue enc;

        IntAccessor(IntEncodedValue enc, boolean inverted) {
            super(inverted);
            this.enc = enc;
        }

        @Override
        Object get(EvaluationContext ctx) {
            return useReverse(ctx) ? ctx.edge.getReverse(enc) : ctx.edge.get(enc);
        }
    }

    private static final class EnumAccessor extends VariableAccessor {
        private final EnumEncodedValue<?> enc;
        private final EnumLookup lookup;

        EnumAccessor(EnumEncodedValue<?> enc, boolean inverted, EnumLookup lookup) {
            super(inverted);
            this.enc = enc;
            this.lookup = lookup;
        }

        @Override
        Object get(EvaluationContext ctx) {
            Object result = useReverse(ctx) ? ctx.edge.getReverse(enc) : ctx.edge.get(enc);
            if (result instanceof Number)
                return lookup.constantByOrdinal(((Number) result).intValue());
            return result;
        }
    }

    private static final class StringAccessor extends VariableAccessor {
        private final StringEncodedValue enc;

        StringAccessor(StringEncodedValue enc, boolean inverted) {
            super(inverted);
            this.enc = enc;
        }

        @Override
        Object get(EvaluationContext ctx) {
            return useReverse(ctx) ? ctx.edge.getReverse(enc) : ctx.edge.get(enc);
        }
    }

    private static final class EnumLookup {
        private final Map<String, Enum<?>> valuesByName;
        private final Enum<?>[] valuesByOrdinal;

        EnumLookup(Enum<?>[] constants) {
            valuesByName = new LinkedHashMap<>(constants.length);
            valuesByOrdinal = constants;
            for (Enum<?> enumConstant : constants) {
                valuesByName.put(enumConstant.name(), enumConstant);
            }
        }

        Enum<?> constant(String name) {
            Enum<?> enumConstant = valuesByName.get(name);
            if (enumConstant == null)
                throw new IllegalArgumentException("Enum constant '" + name + "' not available");
            return enumConstant;
        }

        Enum<?> constantByOrdinal(int ordinal) {
            if (ordinal < 0 || ordinal >= valuesByOrdinal.length)
                throw new IllegalArgumentException("Enum ordinal " + ordinal + " out of range");
            return valuesByOrdinal[ordinal];
        }
    }

    private static final class ExpressionEvaluator {
        static boolean evaluateBoolean(Java.Rvalue expression, EvaluationContext ctx) {
            Object value = evaluate(expression, ctx);
            if (value instanceof Boolean)
                return (Boolean) value;
            if (value instanceof Number)
                return ((Number) value).doubleValue() != 0d;
            if (value instanceof String) {
                String str = ((String) value).trim();
                if (str.equalsIgnoreCase("true")) return true;
                if (str.equalsIgnoreCase("false")) return false;
                try {
                    return Double.parseDouble(str) != 0d;
                } catch (NumberFormatException ignored) {
                }
            }
            throw new IllegalArgumentException("Expression did not evaluate to a boolean (" +
                    (value == null ? "null" : value.getClass().getSimpleName()) + ")");
        }

        static double evaluateDouble(Java.Rvalue expression, EvaluationContext ctx, String valueSource) {
            Object value = evaluate(expression, ctx);
            if (value instanceof Number)
                return ((Number) value).doubleValue();
            if (value instanceof String) {
                try {
                    return Double.parseDouble(((String) value).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            throw new IllegalArgumentException("Expression '" + valueSource + "' did not evaluate to a number (" +
                    (value == null ? "null" : value.getClass().getSimpleName()) + ")");
        }

        private static Object evaluate(Java.Atom expression, EvaluationContext ctx) {
            if (expression instanceof Java.ParenthesizedExpression)
                return evaluate(((Java.ParenthesizedExpression) expression).value, ctx);
            if (expression instanceof Java.AmbiguousName)
                return evaluateAmbiguousName((Java.AmbiguousName) expression, ctx);
            if (expression instanceof Java.BooleanLiteral) {
                Object boolVal = ((Java.BooleanLiteral) expression).value;
                if (boolVal instanceof String)
                    return Boolean.parseBoolean(((String) boolVal).trim());
                return boolVal;
            }
            if (expression instanceof Java.Literal) {
                Object literalValue = ((Java.Literal) expression).value;
                if (literalValue instanceof String) {
                    String literalString = ((String) literalValue).trim();
                    if ("true".equalsIgnoreCase(literalString))
                        return Boolean.TRUE;
                    if ("false".equalsIgnoreCase(literalString))
                        return Boolean.FALSE;
                }
                return literalValue;
            }
            if (expression instanceof Java.NullLiteral)
                return null;
            if (expression instanceof Java.UnaryOperation)
                return evaluateUnary((Java.UnaryOperation) expression, ctx);
            if (expression instanceof Java.BinaryOperation)
                return evaluateBinary((Java.BinaryOperation) expression, ctx);
            if (expression instanceof Java.MethodInvocation)
                return evaluateMethodInvocation((Java.MethodInvocation) expression, ctx);
            if (expression instanceof Java.FieldAccess)
                return evaluateFieldAccess((Java.FieldAccess) expression, ctx);
            if (expression instanceof Java.FieldAccessExpression)
                return evaluateFieldAccessExpression((Java.FieldAccessExpression) expression, ctx);
            if (expression instanceof Java.ThisReference)
                return ctx.helper;
            throw new IllegalArgumentException("Unsupported expression " + expression.getClass().getSimpleName());
        }

        private static Object evaluateAmbiguousName(Java.AmbiguousName name, EvaluationContext ctx) {
            String[] identifiers = name.identifiers;
            if (identifiers.length == 1) {
                String id = identifiers[0];
                if ("this".equals(id))
                    return ctx.helper;
                if ("edge".equals(id))
                    return ctx.edge;
                if ("Math".equals(id))
                    return Math.class;
                if ("CustomWeightingHelper".equals(id))
                    return CustomWeightingHelper.class;
                VariableAccessor accessor = ctx.helper.resolveVariableAccessor(id);
                if (accessor != null)
                    return accessor.get(ctx);
                throw new IllegalArgumentException("Unknown identifier '" + id + "'");
            }
            if (identifiers.length == 2) {
                if ("CustomWeightingHelper".equals(identifiers[0]))
                    return CustomWeightingHelper.class;
                if ("this".equals(identifiers[0]))
                    return ctx.helper.resolveAreaByName(identifiers[1]);
                EnumLookup lookup = ctx.helper.resolveEnumLookup(identifiers[0]);
                if (lookup != null)
                    return lookup.constant(identifiers[1]);
                VariableAccessor nestedAccessor = ctx.helper.resolveVariableAccessor(identifiers[0]);
                if (nestedAccessor != null && "ordinal".equals(identifiers[1]))
                    return nestedAccessor.get(ctx);
                try {
                    Class<?> enumClass = Class.forName("com.graphhopper.routing.ev." + identifiers[0]);
                    if (Enum.class.isAssignableFrom(enumClass)) {
                        @SuppressWarnings("unchecked")
                        Enum<?> enumConstant = Enum.valueOf((Class<? extends Enum>) enumClass, identifiers[1]);
                        return enumConstant;
                    }
                } catch (ClassNotFoundException ignored) {
                }
                if ("Math".equals(identifiers[0])) {
                    if ("PI".equals(identifiers[1]))
                        return Math.PI;
                    if ("E".equals(identifiers[1]))
                        return Math.E;
                }
            }
            throw new IllegalArgumentException("Unknown identifier '" + name + "'");
        }

        private static Object evaluateUnary(Java.UnaryOperation unary, EvaluationContext ctx) {
            Object value = evaluate(unary.operand, ctx);
            switch (unary.operator) {
                case "!":
                    return !toBoolean(value);
                case "-":
                    return -toDouble(value);
                case "+":
                    return toDouble(value);
                default:
                    throw new IllegalArgumentException("Operator '" + unary.operator + "' not supported");
            }
        }

        private static Object evaluateBinary(Java.BinaryOperation binary, EvaluationContext ctx) {
            String op = binary.operator;
            switch (op) {
                case "&&":
                    return evaluateBoolean(binary.lhs, ctx) && evaluateBoolean(binary.rhs, ctx);
                case "||":
                    return evaluateBoolean(binary.lhs, ctx) || evaluateBoolean(binary.rhs, ctx);
                case "==":
                    Object leftEq = evaluate(binary.lhs, ctx);
                    Object rightEq = evaluate(binary.rhs, ctx);
                    return Objects.equals(leftEq, rightEq);
                case "!=":
                    return !Objects.equals(evaluate(binary.lhs, ctx), evaluate(binary.rhs, ctx));
                case "<":
                    return toDouble(evaluate(binary.lhs, ctx)) < toDouble(evaluate(binary.rhs, ctx));
                case "<=":
                    return toDouble(evaluate(binary.lhs, ctx)) <= toDouble(evaluate(binary.rhs, ctx));
                case ">":
                    return toDouble(evaluate(binary.lhs, ctx)) > toDouble(evaluate(binary.rhs, ctx));
                case ">=":
                    return toDouble(evaluate(binary.lhs, ctx)) >= toDouble(evaluate(binary.rhs, ctx));
                case "+": {
                    Object left = evaluate(binary.lhs, ctx);
                    Object right = evaluate(binary.rhs, ctx);
                    if (left instanceof String || right instanceof String)
                        return String.valueOf(left) + right;
                    return toDouble(left) + toDouble(right);
                }
                case "-":
                    return toDouble(evaluate(binary.lhs, ctx)) - toDouble(evaluate(binary.rhs, ctx));
                case "*":
                    return toDouble(evaluate(binary.lhs, ctx)) * toDouble(evaluate(binary.rhs, ctx));
                default:
                    throw new IllegalArgumentException("Operator '" + op + "' not supported");
            }
        }

        private static Object evaluateMethodInvocation(Java.MethodInvocation invocation, EvaluationContext ctx) {
            if (invocation.target == null)
                throw new IllegalArgumentException("Method invocation without target unsupported");
            Object target = evaluate(invocation.target, ctx);
            List<Object> args = new ArrayList<>(invocation.arguments.length);
            for (Java.Rvalue argument : invocation.arguments) {
                args.add(evaluate(argument, ctx));
            }
            String method = invocation.methodName;
            if (target == Math.class) {
                if ("sqrt".equals(method))
                    return Math.sqrt(toDouble(args.get(0)));
                if ("abs".equals(method))
                    return Math.abs(toDouble(args.get(0)));
            } else if (target == CustomWeightingHelper.class) {
                if ("in".equals(method)) {
                    if (invocation.arguments.length != 2)
                        throw new IllegalArgumentException("CustomWeightingHelper.in expects two arguments");
                    Polygon area = (Polygon) args.get(0);
                    return CustomWeightingHelper.in(area, ctx.edge);
                }
            } else if (target instanceof EdgeIteratorState) {
                EdgeIteratorState edge = (EdgeIteratorState) target;
                if ("getDistance".equals(method))
                    return edge.getDistance();
                if ("getName".equals(method))
                    return edge.getName();
            } else if (target instanceof Enum<?>) {
                if ("ordinal".equals(method))
                    return ((Enum<?>) target).ordinal();
            } else if (target instanceof CharSequence) {
                if ("contains".equals(method))
                    return target.toString().contains(String.valueOf(args.get(0)));
            }
            throw new IllegalArgumentException("Method " + method + " not supported in custom model expressions");
        }

        private static Object evaluateFieldAccess(Java.FieldAccess access, EvaluationContext ctx) {
            String representation = access.toString();
            if (representation.startsWith("this."))
                return ctx.helper.resolveAreaByName(representation.substring("this.".length()));
            throw new IllegalArgumentException("Unsupported field access '" + representation + "'");
        }

        private static Object evaluateFieldAccessExpression(Java.FieldAccessExpression access, EvaluationContext ctx) {
            String representation = access.toString();
            if (representation.startsWith("this."))
                return ctx.helper.resolveAreaByName(representation.substring("this.".length()));
            throw new IllegalArgumentException("Unsupported field access '" + representation + "'");
        }

        private static boolean toBoolean(Object value) {
            if (value instanceof Boolean)
                return (Boolean) value;
            if (value instanceof Number)
                return ((Number) value).doubleValue() != 0d;
            if (value instanceof String) {
                String str = ((String) value).trim();
                if (str.equalsIgnoreCase("true")) return true;
                if (str.equalsIgnoreCase("false")) return false;
                try {
                    return Double.parseDouble(str) != 0d;
                } catch (NumberFormatException ignored) {
                }
            }
            throw new IllegalArgumentException("Cannot convert " + value + " to boolean");
        }

        private static double toDouble(Object value) {
            if (value instanceof Number)
                return ((Number) value).doubleValue();
            if (value instanceof String) {
                try {
                    return Double.parseDouble(((String) value).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            throw new IllegalArgumentException("Cannot convert " + value + " to number");
        }
    }
}
