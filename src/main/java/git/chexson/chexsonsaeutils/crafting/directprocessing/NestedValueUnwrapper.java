package git.chexson.chexsonsaeutils.crafting.directprocessing;

import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import appeng.api.stacks.GenericStack;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class NestedValueUnwrapper {

    private static final int MAX_NESTED_MEMBERS = 8;
    private static final Set<String> INPUT_MEMBER_NAMES = Set.of(
            "input",
            "inputs",
            "ingredient",
            "ingredients",
            "stack",
            "stacks",
            "item",
            "items",
            "fluid",
            "fluids",
            "value",
            "values"
    );
    private static final Set<String> OUTPUT_MEMBER_NAMES = Set.of(
            "output",
            "outputs",
            "result",
            "results",
            "stack",
            "stacks",
            "item",
            "items",
            "fluid",
            "fluids",
            "value",
            "values"
    );
    private static final Set<String> COUNT_MEMBER_NAMES = Set.of(
            "count",
            "counts",
            "amount",
            "amounts",
            "quantity",
            "quantities",
            "size",
            "sizes",
            "requiredcount",
            "requiredamount",
            "ingredientcount",
            "ingredientamount",
            "inputcount",
            "inputamount"
    );

    List<Object> unwrapInputValues(Object owner) {
        return unwrap(owner, INPUT_MEMBER_NAMES);
    }

    List<Object> unwrapOutputValues(Object owner) {
        return unwrap(owner, OUTPUT_MEMBER_NAMES);
    }

    long readInputMultiplier(Object owner) {
        return readMultiplier(owner);
    }

    private List<Object> unwrap(Object owner, Set<String> allowedNames) {
        if (owner == null || isDirectSupportedValue(owner.getClass())) {
            return List.of();
        }
        List<ReadableMember> readableMembers = collectMembers(owner.getClass(), allowedNames);
        if (readableMembers.isEmpty()) {
            return List.of();
        }
        List<Object> values = new ArrayList<>();
        for (ReadableMember member : readableMembers) {
            Object value = member.read(owner);
            if (value != null) {
                values.add(value);
            }
        }
        return values.isEmpty() ? List.of() : List.copyOf(values);
    }

    private long readMultiplier(Object owner) {
        if (owner == null || isDirectSupportedValue(owner.getClass())) {
            return 1L;
        }
        List<ReadableMember> members = collectMembers(owner.getClass(), COUNT_MEMBER_NAMES, NestedValueUnwrapper::isNumericReadableType);
        if (members.isEmpty()) {
            return 1L;
        }
        for (ReadableMember member : members) {
            Object value = member.read(owner);
            if (value instanceof Number number) {
                long multiplier = number.longValue();
                if (multiplier > 0L) {
                    return multiplier;
                }
            }
        }
        return 1L;
    }

    private static List<ReadableMember> collectMembers(Class<?> type, Set<String> allowedNames) {
        return collectMembers(type, allowedNames, NestedValueUnwrapper::isReadableMemberType);
    }

    private static List<ReadableMember> collectMembers(
            Class<?> type,
            Set<String> allowedNames,
            java.util.function.Predicate<Class<?>> typeFilter
    ) {
        List<ReadableMember> namedMembers = new ArrayList<>();
        List<ReadableMember> fallbackMembers = new ArrayList<>();
        Set<String> seenSignatures = new LinkedHashSet<>();
        Set<String> seenLogicalNames = new LinkedHashSet<>();

        for (Class<?> currentClass = type;
            currentClass != null && currentClass != Object.class;
             currentClass = currentClass.getSuperclass()) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (namedMembers.size() + fallbackMembers.size() >= MAX_NESTED_MEMBERS) {
                    break;
                }
                if (field == null || Modifier.isStatic(field.getModifiers()) || !typeFilter.test(field.getType())) {
                    continue;
                }
                if (!tryMakeAccessible(field)) {
                    continue;
                }
                FieldMember member = new FieldMember(field);
                if (!seenSignatures.add(member.signature())) {
                    continue;
                }
                String logicalName = normalizeName(field.getName());
                if (logicalName.isEmpty() || !seenLogicalNames.add(logicalName)) {
                    continue;
                }
                if (matchesAllowedName(logicalName, allowedNames)) {
                    namedMembers.add(member);
                } else {
                    fallbackMembers.add(member);
                }
            }
        }

        for (Method method : type.getMethods()) {
            if (namedMembers.size() + fallbackMembers.size() >= MAX_NESTED_MEMBERS) {
                break;
            }
            if (method == null
                    || method.getParameterCount() != 0
                    || Modifier.isStatic(method.getModifiers())
                    || method.getDeclaringClass() == Object.class
                    || !typeFilter.test(method.getReturnType())) {
                continue;
            }
            String effectiveName = accessorMemberName(method.getName());
            if (effectiveName.isEmpty()) {
                continue;
            }
            AccessorMember member = new AccessorMember(method);
            if (!seenSignatures.add(member.signature())) {
                continue;
            }
            if (!seenLogicalNames.add(effectiveName)) {
                continue;
            }
            if (matchesAllowedName(effectiveName, allowedNames)) {
                namedMembers.add(member);
            } else {
                fallbackMembers.add(member);
            }
        }

        if (!namedMembers.isEmpty()) {
            return List.copyOf(namedMembers);
        }
        if (fallbackMembers.size() == 1) {
            return List.copyOf(fallbackMembers);
        }
        return List.of();
    }

    private static boolean isDirectSupportedValue(Class<?> type) {
        return Ingredient.class.isAssignableFrom(type)
                || ItemStack.class.isAssignableFrom(type)
                || FluidStack.class.isAssignableFrom(type)
                || GenericStack.class.isAssignableFrom(type)
                || Iterable.class.isAssignableFrom(type)
                || type.isArray();
    }

    private static boolean isReadableMemberType(Class<?> type) {
        return type != null && !type.isPrimitive() && type != Void.TYPE;
    }

    private static boolean isNumericReadableType(Class<?> type) {
        return type != null
                && (Number.class.isAssignableFrom(type)
                || type == Integer.TYPE
                || type == Long.TYPE
                || type == Short.TYPE
                || type == Byte.TYPE);
    }

    private static boolean matchesAllowedName(String name, Set<String> allowedNames) {
        String normalized = normalizeName(name);
        if (normalized.isEmpty()) {
            return false;
        }
        if (allowedNames.contains(normalized)) {
            return true;
        }
        for (String allowedName : allowedNames) {
            if (normalized.endsWith(allowedName)) {
                return true;
            }
        }
        return false;
    }

    private static String accessorMemberName(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return "";
        }
        String normalized = normalizeName(methodName);
        if (normalized.startsWith("get") && normalized.length() > 3) {
            return normalized.substring(3);
        }
        if (normalized.startsWith("is") && normalized.length() > 2) {
            return normalized.substring(2);
        }
        return normalized;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static boolean tryMakeAccessible(Field field) {
        try {
            field.setAccessible(true);
            return true;
        } catch (SecurityException | InaccessibleObjectException ignored) {
            return false;
        }
    }

    private sealed interface ReadableMember permits FieldMember, AccessorMember {
        Object read(Object owner);

        String signature();
    }

    private record FieldMember(Field field) implements ReadableMember {
        @Override
        public Object read(Object owner) {
            try {
                return field.get(owner);
            } catch (IllegalAccessException | RuntimeException ignored) {
                return null;
            }
        }

        @Override
        public String signature() {
            return "field:" + field.getDeclaringClass().getName() + ':' + field.getName();
        }
    }

    private record AccessorMember(Method method) implements ReadableMember {
        @Override
        public Object read(Object owner) {
            try {
                return method.invoke(owner);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }

        @Override
        public String signature() {
            return "method:" + method.getDeclaringClass().getName() + ':' + method.getName();
        }
    }
}
