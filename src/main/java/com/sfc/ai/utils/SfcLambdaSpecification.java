package com.sfc.ai.utils;

import com.sfc.common.utils.SfcClassUtils;
import com.sfc.common.utils.SfcFunc;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 基于 getter 方法引用（{@code Entity::getField}）的 JPA 条件构造器。
 *
 * <p><b>为什么本模块需要它：</b>宿主工具类 {@code JpaLambdaQueryWrapper} 位于 {@code sfc-api}，
 * 微服务化后不可用（零宿主依赖）。本类为它在 {@code sfc-ai-service} 内的等价替代：
 * 以相同的链式风格声明条件，最终交给 {@code JpaSpecificationExecutor.findAll(Specification, Pageable)}。</p>
 *
 * <p><b>用法（与宿主写法一一对应）：</b></p>
 * <pre>{@code
 * Specification<SensitiveToolCallRecord> spec = SfcLambdaSpecification.get(SensitiveToolCallRecord.class)
 *         .eq(SensitiveToolCallRecord::getUid, param.getUid())
 *         .like(SensitiveToolCallRecord::getToolName, param.getToolName())
 *         .ge(SensitiveToolCallRecord::getCreateAt, param.getStartTime())
 *         .le(SensitiveToolCallRecord::getCreateAt, param.getEndTime())
 *         .build();
 * }</pre>
 *
 * <p><b>条件值语义：</b>值为 {@code null} 的条件<b>直接忽略</b>（动态条件惯用法，与宿主
 * {@code NullStrategy.IGNORE} 默认策略一致）；空串等非 null 值照常参与比较。</p>
 *
 * <p><b>与宿主 {@code JpaLambdaQueryWrapper} 的刻意差异（两处均为宿主实现的缺陷，迁移时一并订正）：</b></p>
 * <ul>
 *   <li><b>关系运算符方向：</b>宿主实现里 {@code ge} 实际生成 {@code >}、{@code gt} 生成 {@code >=}
 *       （两个分支写反了）。本类严格按方法名语义生成 {@code >=} / {@code <=}。</li>
 *   <li><b>比较值类型适配：</b>调用方传毫秒时间戳（{@code Long}）而实体属性是
 *       {@code java.util.Date} 时，宿主直接把 {@code Number} 交给 {@code CriteriaBuilder}
 *       （Hibernate 6+ 会因类型不匹配报错）。本类按目标属性的 Java 类型自动换算。</li>
 * </ul>
 *
 * <p>能力范围刻意收窄为本模块实际用到的 4 个条件（{@code eq} / {@code like} / {@code ge} / {@code le}）；
 * 排序统一由 {@code Pageable} 的 {@code Sort} 承担，不在本类中表达。</p>
 *
 * @param <T> 实体类型
 * @see Specification
 */
public final class SfcLambdaSpecification<T> {

    /** 条件列表，按声明顺序参与 AND 组合 */
    private final List<Condition> conditions = new ArrayList<>();

    /** 工具类不允许直接实例化，请使用 {@link #get(Class)} */
    private SfcLambdaSpecification() {
    }

    /**
     * 创建条件构造器。
     *
     * @param entityClass 实体类型（仅用于类型推导，不参与运行期判定）
     * @param <T>         实体类型
     * @return 条件构造器
     */
    public static <T> SfcLambdaSpecification<T> get(Class<T> entityClass) {
        return new SfcLambdaSpecification<>();
    }

    /**
     * 追加相等条件（{@code field = value}）。
     *
     * @param getter 实体字段的 getter 方法引用，如 {@code Entity::getUid}
     * @param value  比较值；为 {@code null} 时忽略本条件
     * @param <R>    字段类型
     * @return 自身，便于链式调用
     */
    public <R> SfcLambdaSpecification<T> eq(SfcFunc<T, R> getter, Object value) {
        return add(getter, Operator.EQ, value);
    }

    /**
     * 追加模糊匹配条件（{@code field LIKE value}）。
     *
     * <p>与宿主实现一致：通配符由调用方自行携带（本方法不会自动包 {@code %}）。</p>
     *
     * @param getter 实体字段的 getter 方法引用，如 {@code Entity::getToolName}
     * @param value  匹配模式；为 {@code null} 时忽略本条件
     * @param <R>    字段类型
     * @return 自身，便于链式调用
     */
    public <R> SfcLambdaSpecification<T> like(SfcFunc<T, R> getter, String value) {
        return add(getter, Operator.LIKE, value);
    }

    /**
     * 追加大于等于条件（{@code field >= value}）。
     *
     * @param getter 实体字段的 getter 方法引用，如 {@code Entity::getCreateAt}
     * @param value  比较值；为 {@code null} 时忽略本条件
     * @param <R>    字段类型
     * @return 自身，便于链式调用
     */
    public <R> SfcLambdaSpecification<T> ge(SfcFunc<T, R> getter, Object value) {
        return add(getter, Operator.GE, value);
    }

    /**
     * 追加小于等于条件（{@code field <= value}）。
     *
     * @param getter 实体字段的 getter 方法引用，如 {@code Entity::getCreateAt}
     * @param value  比较值；为 {@code null} 时忽略本条件
     * @param <R>    字段类型
     * @return 自身，便于链式调用
     */
    public <R> SfcLambdaSpecification<T> le(SfcFunc<T, R> getter, Object value) {
        return add(getter, Operator.LE, value);
    }

    /**
     * 构建 Spring Data JPA 条件对象。
     *
     * @return 条件对象；未声明任何条件时返回恒真条件（{@code 1 = 1}），即不添加任何过滤
     */
    public Specification<T> build() {
        List<Condition> snapshot = List.copyOf(conditions);
        return (root, query, cb) -> {
            if (snapshot.isEmpty()) {
                return cb.conjunction();
            }
            List<Predicate> predicates = new ArrayList<>(snapshot.size());
            for (Condition condition : snapshot) {
                predicates.add(toPredicate(root, cb, condition));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * 追加一个条件（值为 {@code null} 时忽略）。
     *
     * @param getter   实体字段的 getter 方法引用
     * @param operator 比较运算符
     * @param value    比较值
     * @param <R>      字段类型
     * @return 自身，便于链式调用
     */
    private <R> SfcLambdaSpecification<T> add(SfcFunc<T, R> getter, Operator operator, Object value) {
        if (value == null) {
            return this;
        }
        conditions.add(new Condition(SfcClassUtils.parseGetterLambdaMetaData(getter).fieldName(), operator, value));
        return this;
    }

    /**
     * 把单个条件翻译为 JPA {@link Predicate}。
     *
     * @param root      查询根
     * @param cb        条件构造器
     * @param condition 条件定义
     * @param <T>       实体类型
     * @return JPA 断言
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Predicate toPredicate(Root<T> root, CriteriaBuilder cb, Condition condition) {
        String field = condition.field();
        Object value = coerceValue(root.get(field), condition.value());
        return switch (condition.operator()) {
            case EQ -> cb.equal(root.get(field), value);
            case LIKE -> cb.like(root.<String>get(field), (String) value);
            // 关系比较统一按 Comparable 走：属性的实际 Java 类型（Number / Date / String）都由 Hibernate 解析
            case GE -> cb.greaterThanOrEqualTo(root.get(field), (Comparable) value);
            case LE -> cb.lessThanOrEqualTo(root.get(field), (Comparable) value);
        };
    }

    /**
     * 按目标属性的 Java 类型换算比较值。
     *
     * <p>本模块的典型场景是「查询参数为毫秒时间戳（{@code Long}）而实体属性为
     * {@code java.util.Date}」（见 {@code SensitiveToolCallQueryParam#getStartTime()}），
     * 宿主实现把 {@code Number} 原样交给 {@code CriteriaBuilder}，Hibernate 6+ 下类型不匹配；
     * 这里统一做一次等价换算。</p>
     *
     * @param path  目标属性路径
     * @param value 原始比较值
     * @return 与属性类型匹配的比较值
     */
    private static Object coerceValue(Path<?> path, Object value) {
        Class<?> targetType = path.getJavaType();
        if (value instanceof Number number) {
            if (Date.class.isAssignableFrom(targetType)) {
                return new Date(number.longValue());
            }
            return value;
        }
        if (value instanceof Date date && Number.class.isAssignableFrom(targetType)) {
            return date.getTime();
        }
        return value;
    }

    /**
     * 比较运算符。
     */
    private enum Operator {
        /** 相等 */
        EQ,
        /** 模糊匹配（通配符由调用方携带） */
        LIKE,
        /** 大于等于 */
        GE,
        /** 小于等于 */
        LE
    }

    /**
     * 单个查询条件。
     *
     * @param field    实体属性名（由 getter 方法引用推导）
     * @param operator 比较运算符
     * @param value    比较值（非 null）
     */
    private record Condition(String field, Operator operator, Object value) {
    }
}
