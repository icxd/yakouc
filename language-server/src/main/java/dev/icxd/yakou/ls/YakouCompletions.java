package dev.icxd.yakou.ls;

import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.ClassMethod;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.ast.FieldParam;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.cp.ClasspathIndex;
import dev.icxd.yakou.cp.JavaMemberCompletion;
import dev.icxd.yakou.cp.JavaMemberCompletion.MemberProposal;
import dev.icxd.yakou.typeck.Ty;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class YakouCompletions {

    private YakouCompletions() {
    }

    static Either<List<CompletionItem>, CompletionList> complete(
            CompletionParams params, String text, YakouServerState state) {
        List<CompletionItem> out = new ArrayList<>();
        var pos = params.getPosition();
        int offset = LspPositions.utf16Offset(text, pos);
        String prefix = wordPrefix(text, offset);

        out.addAll(filterKeywords(prefix));

        Optional<AstFile> ast = YakouAnalysis.parseOnly(params.getTextDocument().getUri(), text, state);
        Optional<PathContext> pathCtx = parsePathContext(text, offset, pos.getLine());
        if (pathCtx.isPresent()) {
            PathContext pc = pathCtx.get();
            ClasspathIndex ix = state.classpathIndex();
            for (String seg : ix.completeYkPathPrefix(pc.parentYk())) {
                if (!pc.segmentPrefix().isEmpty()
                        && !seg.toLowerCase(Locale.ROOT)
                                .startsWith(pc.segmentPrefix().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                CompletionItem it = new CompletionItem();
                it.setLabel(seg);
                it.setKind(CompletionItemKind.Module);
                it.setInsertText(seg);
                it.setDetail("classpath");
                out.add(it);
            }
        }

        Optional<Integer> dot = dotOperatorIndex(text, offset);
        // Merge scope identifiers even after `.` so params/locals like `self` stay
        // visible
        // alongside member proposals when completing `self.` etc.
        if (pathCtx.isEmpty()) {
            ast.ifPresent(
                    a -> {
                        for (CompletionScopes.ScopedName sn : CompletionScopes.visible(a, offset)) {
                            if (!prefix.isEmpty()
                                    && !sn.name()
                                            .toLowerCase(Locale.ROOT)
                                            .startsWith(prefix.toLowerCase(Locale.ROOT))) {
                                continue;
                            }
                            CompletionItem it = new CompletionItem();
                            it.setLabel(sn.name());
                            it.setKind(
                                    switch (sn.kind()) {
                                        case FUNCTION -> CompletionItemKind.Function;
                                        case CLASS -> CompletionItemKind.Class;
                                        case MODULE -> CompletionItemKind.Module;
                                        case VARIABLE -> CompletionItemKind.Variable;
                                    });
                            it.setInsertText(sn.name());
                            it.setDetail("scope");
                            out.add(it);
                        }
                    });
        }

        if (dot.isPresent()) {
            Optional<YakouAnalysis.Result> ar = YakouAnalysis.analyze(params.getTextDocument().getUri(), text, state);
            if (ar.isPresent()) {
                YakouAnalysis.Result r = ar.get();
                Optional<Expr> recv = CompletionExprs.receiverExprBeforeDot(r.ast(), dot.get());
                if (recv.isPresent()) {
                    Ty ty = Ty.deref(r.exprTypes().get(recv.get()));
                    if (ty instanceof Ty.NomTy n) {
                        String pfx = memberPrefixAfterDot(text, offset, dot.get());
                        Optional<Item.Class> ykCl = NominalAst.findClassRelaxed(r.ast().items(), n.path());
                        Set<String> ykMethodNames = new HashSet<>();
                        ykCl.ifPresent(
                                cl -> {
                                    for (FieldParam fp : cl.ctorFields()) {
                                        if (!pfx.isEmpty()
                                                && !fp.name()
                                                        .toLowerCase(Locale.ROOT)
                                                        .startsWith(pfx.toLowerCase(Locale.ROOT))) {
                                            continue;
                                        }
                                        CompletionItem fit = new CompletionItem();
                                        fit.setLabel(fp.name());
                                        fit.setKind(CompletionItemKind.Field);
                                        fit.setInsertText(fp.name());
                                        fit.setDetail("yakou field");
                                        out.add(fit);
                                    }
                                    for (ClassMethod cm : cl.methods()) {
                                        if (!pfx.isEmpty()
                                                && !cm.name()
                                                        .toLowerCase(Locale.ROOT)
                                                        .startsWith(
                                                                pfx.toLowerCase(Locale.ROOT))) {
                                            continue;
                                        }
                                        ykMethodNames.add(cm.name());
                                        CompletionItem it = new CompletionItem();
                                        it.setLabel(cm.name());
                                        it.setKind(CompletionItemKind.Method);
                                        it.setInsertText(cm.name() + "(");
                                        it.setDetail("yakou");
                                        out.add(it);
                                    }
                                });
                        if (r.javaInterop().hasLoadableClass(n.path())) {
                            for (MemberProposal mp : JavaMemberCompletion.propose(
                                    r.javaInterop(), n.path(), pfx)) {
                                boolean instMeth = mp.kind() == JavaMemberCompletion.MemberKind.INSTANCE_METHOD;
                                if (instMeth && ykMethodNames.contains(mp.label())) {
                                    continue;
                                }
                                CompletionItem it = new CompletionItem();
                                it.setLabel(mp.label());
                                it.setKind(
                                        mp.kind() == JavaMemberCompletion.MemberKind.INSTANCE_METHOD
                                                || mp.kind() == JavaMemberCompletion.MemberKind.STATIC_METHOD
                                                        ? CompletionItemKind.Method
                                                        : CompletionItemKind.Field);
                                it.setInsertText(mp.insertText());
                                it.setDetail(mp.detail());
                                out.add(it);
                            }
                        }
                    }
                }
            }
        }

        out.sort(Comparator.comparing(CompletionItem::getLabel, String.CASE_INSENSITIVE_ORDER));
        return Either.forLeft(out);
    }

    private static String wordPrefix(String text, int offset) {
        int i = offset;
        while (i > 0 && identChar(text.charAt(i - 1))) {
            i--;
        }
        return text.substring(i, offset);
    }

    private static List<CompletionItem> filterKeywords(String prefix) {
        List<CompletionItem> kws = new ArrayList<>();
        String p = prefix == null ? "" : prefix;
        for (String kw : YakouKeywords.ALL) {
            if (p.isEmpty()
                    || kw.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT))) {
                CompletionItem it = new CompletionItem();
                it.setLabel(kw);
                it.setKind(CompletionItemKind.Keyword);
                it.setInsertText(kw);
                kws.add(it);
            }
        }
        return kws;
    }

    /**
     * {@code PathContext} holds the parent path (with {@code ::}) and the partial
     * next segment.
     */
    private record PathContext(String parentYk, String segmentPrefix) {
    }

    /**
     * Detects {@code use a::b::…} or {@code …::…} classpath path completion after
     * {@code ::}.
     */
    private static Optional<PathContext> parsePathContext(String text, int offset, int zeroBasedLine) {
        int lineStart = LspPositions.lineStartOffset(text, zeroBasedLine);
        int nextNl = text.indexOf('\n', lineStart);
        int lineEnd = nextNl < 0 ? text.length() : nextNl;
        int lineToCursor = Math.min(offset, lineEnd);
        String linePrefix = text.substring(lineStart, lineToCursor);
        int lineUseRel = linePrefix.lastIndexOf("use ");
        int lineUse = lineUseRel >= 0 ? lineStart + lineUseRel : -1;
        if (lineUse >= lineStart && lineUse < lineEnd && lineUse + 4 < offset) {
            int start = lineUse + 4;
            while (start < lineEnd && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
            String region = text.substring(start, offset).replaceAll("\\s+", "");
            return splitPath(region);
        }

        int p = offset;
        while (p > 0 && identChar(text.charAt(p - 1))) {
            p--;
        }
        if (p >= 2 && text.charAt(p - 1) == ':' && text.charAt(p - 2) == ':') {
            String head = text.substring(lineStart, p - 2).trim();
            int lastUse = head.lastIndexOf("use ");
            if (lastUse >= 0) {
                head = head.substring(lastUse + 4).trim();
            }
            head = head.replaceAll("\\s+", "");
            String partial = text.substring(p, offset);
            String flat = head.isEmpty() ? partial : head + "::" + partial;
            return splitPath(flat);
        }

        return Optional.empty();
    }

    private static Optional<PathContext> splitPath(String flat) {
        String f = flat.trim();
        if (f.isEmpty()) {
            return Optional.empty();
        }
        if (!f.contains("::")) {
            return Optional.of(new PathContext("", f));
        }
        String[] segs = f.split("::", -1);
        if (segs.length == 0) {
            return Optional.empty();
        }
        String partial = segs[segs.length - 1];
        if (segs.length == 1) {
            return Optional.of(new PathContext("", partial));
        }
        StringBuilder parent = new StringBuilder(segs[0]);
        for (int i = 1; i < segs.length - 1; i++) {
            parent.append("::").append(segs[i]);
        }
        return Optional.of(new PathContext(parent.toString(), partial));
    }

    private static Optional<Integer> dotOperatorIndex(String text, int offset) {
        int i = offset;
        while (i > 0 && identChar(text.charAt(i - 1))) {
            i--;
        }
        if (i > 0 && text.charAt(i - 1) == '.') {
            return Optional.of(i - 1);
        }
        return Optional.empty();
    }

    /**
     * Identifier fragment typed after {@code recv.}, excluding leading whitespace /
     * newlines so {@code self.\\n    } still filters members with an empty prefix.
     */
    private static String memberPrefixAfterDot(String text, int cursorOffset, int dotIdx) {
        int start = dotIdx + 1;
        if (start >= cursorOffset) {
            return "";
        }
        String gap = text.substring(start, cursorOffset);
        int i = 0;
        while (i < gap.length() && Character.isWhitespace(gap.charAt(i))) {
            i++;
        }
        int j = i;
        while (j < gap.length() && identChar(gap.charAt(j))) {
            j++;
        }
        return gap.substring(i, j);
    }

    private static boolean identChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
