/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.join;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.capabilities.PostAnalysisVerificationAware;
import org.elasticsearch.xpack.esql.common.Failures;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.AttributeSet;
import org.elasticsearch.xpack.esql.core.expression.Expressions;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;
import org.elasticsearch.xpack.esql.plan.logical.BinaryPlan;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.SortAgnostic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.xpack.esql.common.Failure.fail;
import static org.elasticsearch.xpack.esql.core.type.DataType.AGGREGATE_METRIC_DOUBLE;
import static org.elasticsearch.xpack.esql.core.type.DataType.CARTESIAN_POINT;
import static org.elasticsearch.xpack.esql.core.type.DataType.CARTESIAN_SHAPE;
import static org.elasticsearch.xpack.esql.core.type.DataType.COUNTER_DOUBLE;
import static org.elasticsearch.xpack.esql.core.type.DataType.COUNTER_INTEGER;
import static org.elasticsearch.xpack.esql.core.type.DataType.COUNTER_LONG;
import static org.elasticsearch.xpack.esql.core.type.DataType.DATE_PERIOD;
import static org.elasticsearch.xpack.esql.core.type.DataType.DENSE_VECTOR;
import static org.elasticsearch.xpack.esql.core.type.DataType.DOC_DATA_TYPE;
import static org.elasticsearch.xpack.esql.core.type.DataType.GEO_POINT;
import static org.elasticsearch.xpack.esql.core.type.DataType.GEO_SHAPE;
import static org.elasticsearch.xpack.esql.core.type.DataType.NULL;
import static org.elasticsearch.xpack.esql.core.type.DataType.OBJECT;
import static org.elasticsearch.xpack.esql.core.type.DataType.PARTIAL_AGG;
import static org.elasticsearch.xpack.esql.core.type.DataType.SOURCE;
import static org.elasticsearch.xpack.esql.core.type.DataType.TEXT;
import static org.elasticsearch.xpack.esql.core.type.DataType.TIME_DURATION;
import static org.elasticsearch.xpack.esql.core.type.DataType.TSID_DATA_TYPE;
import static org.elasticsearch.xpack.esql.core.type.DataType.UNSIGNED_LONG;
import static org.elasticsearch.xpack.esql.core.type.DataType.UNSUPPORTED;
import static org.elasticsearch.xpack.esql.core.type.DataType.VERSION;
import static org.elasticsearch.xpack.esql.expression.NamedExpressions.mergeOutputAttributes;
import static org.elasticsearch.xpack.esql.plan.logical.join.JoinTypes.LEFT;
import static org.elasticsearch.xpack.esql.type.EsqlDataTypeConverter.commonType;

public class Join extends BinaryPlan implements PostAnalysisVerificationAware, SortAgnostic {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(LogicalPlan.class, "Join", Join::new);
    public static final DataType[] UNSUPPORTED_TYPES = {
        TEXT,
        VERSION,
        UNSIGNED_LONG,
        GEO_POINT,
        GEO_SHAPE,
        CARTESIAN_POINT,
        CARTESIAN_SHAPE,
        UNSUPPORTED,
        NULL,
        COUNTER_LONG,
        COUNTER_INTEGER,
        COUNTER_DOUBLE,
        OBJECT,
        SOURCE,
        DATE_PERIOD,
        TIME_DURATION,
        DOC_DATA_TYPE,
        TSID_DATA_TYPE,
        PARTIAL_AGG,
        AGGREGATE_METRIC_DOUBLE,
        DENSE_VECTOR };

    private final JoinConfig config;
    private List<Attribute> lazyOutput;
    // Does this join involve remote indices? This is relevant only on the coordinating node, thus transient.
    private transient boolean isRemote = false;

    public Join(Source source, LogicalPlan left, LogicalPlan right, JoinConfig config) {
        this(source, left, right, config, false);
    }

    public Join(Source source, LogicalPlan left, LogicalPlan right, JoinConfig config, boolean isRemote) {
        super(source, left, right);
        this.config = config;
        this.isRemote = isRemote;
    }

    public Join(
        Source source,
        LogicalPlan left,
        LogicalPlan right,
        JoinType type,
        List<Attribute> matchFields,
        List<Attribute> leftFields,
        List<Attribute> rightFields
    ) {
        super(source, left, right);
        this.config = new JoinConfig(type, matchFields, leftFields, rightFields);
    }

    public Join(StreamInput in) throws IOException {
        super(Source.readFrom((PlanStreamInput) in), in.readNamedWriteable(LogicalPlan.class), in.readNamedWriteable(LogicalPlan.class));
        this.config = new JoinConfig(in);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        source().writeTo(out);
        out.writeNamedWriteable(left());
        out.writeNamedWriteable(right());
        config.writeTo(out);
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    public JoinConfig config() {
        return config;
    }

    @Override
    protected NodeInfo<Join> info() {
        // Do not just add the JoinConfig as a whole - this would prevent correctly registering the
        // expressions and references.
        return NodeInfo.create(
            this,
            Join::new,
            left(),
            right(),
            config.type(),
            config.matchFields(),
            config.leftFields(),
            config.rightFields()
        );
    }

    @Override
    public List<Attribute> output() {
        if (lazyOutput == null) {
            lazyOutput = computeOutput(left().output(), right().output());
        }
        return lazyOutput;
    }

    @Override
    public AttributeSet leftReferences() {
        return Expressions.references(config().leftFields());
    }

    @Override
    public AttributeSet rightReferences() {
        return Expressions.references(config().rightFields());
    }

    /**
     * The output fields obtained from the right child.
     */
    public List<Attribute> rightOutputFields() {
        AttributeSet leftInputs = left().outputSet();

        List<Attribute> rightOutputFields = new ArrayList<>();
        for (Attribute attr : output()) {
            if (leftInputs.contains(attr) == false) {
                rightOutputFields.add(attr);
            }
        }

        return rightOutputFields;
    }

    public List<Attribute> computeOutput(List<Attribute> left, List<Attribute> right) {
        return computeOutput(left, right, config);
    }

    /**
     * Combine the two lists of attributes into one.
     * In case of (name) conflicts, specify which sides wins, that is overrides the other column - the left or the right.
     */
    public static List<Attribute> computeOutput(List<Attribute> leftOutput, List<Attribute> rightOutput, JoinConfig config) {
        JoinType joinType = config.type();
        List<Attribute> output;
        // TODO: make the other side nullable
        if (LEFT.equals(joinType)) {
            // right side becomes nullable and overrides left except for join keys, which we preserve from the left
            AttributeSet rightKeys = AttributeSet.of(config.rightFields());
            List<Attribute> rightOutputWithoutMatchFields = rightOutput.stream().filter(attr -> rightKeys.contains(attr) == false).toList();
            output = mergeOutputAttributes(rightOutputWithoutMatchFields, leftOutput);
        } else {
            throw new IllegalArgumentException(joinType.joinName() + " unsupported");
        }
        return output;
    }

    /**
     * Make fields references, so we don't check if they exist in the index.
     * We do this for fields that we know don't come from the index.
     * <p>
     *   It's important that name is returned as a *reference* here
     *   instead of a field. If it were a field we'd use SearchStats
     *   on it and discover that it doesn't exist in the index. It doesn't!
     *   We don't expect it to. It exists only in the lookup table.
     *   TODO we should rework stats so we don't have to do this
     * </p>
     */
    public static List<Attribute> makeReference(List<Attribute> output) {
        List<Attribute> out = new ArrayList<>(output.size());
        for (Attribute a : output) {
            if (a.resolved() && a instanceof ReferenceAttribute == false) {
                out.add(new ReferenceAttribute(a.source(), a.name(), a.dataType(), a.nullable(), a.id(), a.synthetic()));
            } else {
                out.add(a);
            }
        }
        return out;
    }

    @Override
    public boolean expressionsResolved() {
        return config.expressionsResolved();
    }

    @Override
    public boolean resolved() {
        // resolve the join if
        // - the children are resolved
        // - the condition (if present) is resolved to a boolean
        return childrenResolved() && expressionsResolved();
    }

    public Join withConfig(JoinConfig config) {
        return new Join(source(), left(), right(), config, isRemote);
    }

    @Override
    public Join replaceChildren(LogicalPlan left, LogicalPlan right) {
        return new Join(source(), left, right, config, isRemote);
    }

    @Override
    public int hashCode() {
        return Objects.hash(config, left(), right(), isRemote);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        Join other = (Join) obj;
        return config.equals(other.config)
            && Objects.equals(left(), other.left())
            && Objects.equals(right(), other.right())
            && isRemote == other.isRemote;
    }

    @Override
    public void postAnalysisVerification(Failures failures) {
        for (int i = 0; i < config.leftFields().size(); i++) {
            Attribute leftField = config.leftFields().get(i);
            Attribute rightField = config.rightFields().get(i);
            if (comparableTypes(leftField, rightField) == false) {
                failures.add(
                    fail(
                        leftField,
                        "JOIN left field [{}] of type [{}] is incompatible with right field [{}] of type [{}]",
                        leftField.name(),
                        leftField.dataType(),
                        rightField.name(),
                        rightField.dataType()
                    )
                );
            }
            // TODO: Add support for VERSION by implementing QueryList.versionTermQueryList similar to ipTermQueryList
            if (Arrays.stream(UNSUPPORTED_TYPES).anyMatch(t -> rightField.dataType().equals(t))) {
                failures.add(
                    fail(leftField, "JOIN with right field [{}] of type [{}] is not supported", rightField.name(), rightField.dataType())
                );
            }
        }
    }

    private static boolean comparableTypes(Attribute left, Attribute right) {
        DataType leftType = left.dataType();
        DataType rightType = right.dataType();
        if (leftType.isNumeric() && rightType.isNumeric()) {
            // Allow byte, short, integer, long, half_float, scaled_float, float and double to join against each other
            return commonType(leftType, rightType) != null;
        }
        return leftType.noText() == rightType.noText();
    }

    public boolean isRemote() {
        return isRemote;
    }
}
