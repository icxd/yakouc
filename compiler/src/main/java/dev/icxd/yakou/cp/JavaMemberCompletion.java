package dev.icxd.yakou.cp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

import dev.icxd.yakou.typeck.JavaInterop;

/**
 * Proposes public instance (and static) members of a JVM class for editor
 * completion, using the same classpath as {@link JavaInterop}.
 */
public final class JavaMemberCompletion {

    private JavaMemberCompletion() {
    }

    /** Reflective proposals for {@code receiverYkPath} (a {@code ::} path). */
    public static List<MemberProposal> propose(JavaInterop java, String receiverYkPath, String namePrefix) {
        String internal = JavaInterop.ykPathToInternal(receiverYkPath);
        Optional<Class<?>> oc = java.loadClassFromInternal(internal);
        if (oc.isEmpty()) {
            return List.of();
        }
        Class<?> c = oc.get();
        String pfx = namePrefix == null ? "" : namePrefix;
        TreeSet<MemberProposal> sorted = new TreeSet<>(Comparator.comparing(MemberProposal::insertText));

        for (Method m : c.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            if (!m.getName().toLowerCase(Locale.ROOT).startsWith(pfx.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (m.getName().equals("wait") || m.getName().equals("notify") || m.getName().equals("notifyAll")) {
                if (m.getDeclaringClass().equals(Object.class)) {
                    continue;
                }
            }
            boolean stat = Modifier.isStatic(m.getModifiers());
            String detail = prettyMethod(m);
            sorted.add(
                    new MemberProposal(
                            m.getName(),
                            detail,
                            m.getName() + "(",
                            stat ? MemberKind.STATIC_METHOD : MemberKind.INSTANCE_METHOD));
        }

        for (Field f : c.getFields()) {
            if (!Modifier.isPublic(f.getModifiers())) {
                continue;
            }
            if (!f.getName().toLowerCase(Locale.ROOT).startsWith(pfx.toLowerCase(Locale.ROOT))) {
                continue;
            }
            boolean stat = Modifier.isStatic(f.getModifiers());
            String detail = shortTypeName(f.getType()) + " " + f.getName();
            sorted.add(
                    new MemberProposal(
                            f.getName(),
                            detail,
                            f.getName(),
                            stat ? MemberKind.STATIC_FIELD : MemberKind.INSTANCE_FIELD));
        }

        return new ArrayList<>(sorted);
    }

    private static String prettyMethod(Method m) {
        StringBuilder sb = new StringBuilder();
        if (Modifier.isStatic(m.getModifiers())) {
            sb.append("static ");
        }
        sb.append(shortTypeName(m.getReturnType())).append(' ').append(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(shortTypeName(ps[i]));
        }
        sb.append(')');
        return sb.toString();
    }

    private static String shortTypeName(Class<?> t) {
        if (t.isArray()) {
            return shortTypeName(t.getComponentType()) + "[]";
        }
        if (t.isPrimitive()) {
            return t.getName();
        }
        return t.getSimpleName();
    }

    public enum MemberKind {
        INSTANCE_METHOD,
        STATIC_METHOD,
        INSTANCE_FIELD,
        STATIC_FIELD
    }

    public record MemberProposal(String label, String detail, String insertText, MemberKind kind) {
    }
}
