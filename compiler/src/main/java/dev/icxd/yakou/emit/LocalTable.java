package dev.icxd.yakou.emit;

import dev.icxd.yakou.typeck.Ty;

import java.util.HashMap;
import java.util.Map;

/** Maps local names to JVM local variable slots. */
final class LocalTable {

    private final Map<String, Integer> nameToSlot = new HashMap<>();
    private int nextSlot;

    void bindParam(String name, Ty ty) {
        TyStack sk = TyStack.of(ty);
        int slot = nextSlot;
        nameToSlot.put(name, slot);
        nextSlot += sk.localSlots();
    }

    void bindLocal(String name, Ty ty) {
        bindParam(name, ty);
    }

    /**
     * Reserve JVM slot 0 (or 0–1 for wide) for {@code this} before binding real
     * parameters.
     */
    void skipReceiver(Ty selfTy) {
        nextSlot += TyStack.of(selfTy).localSlots();
    }

    Integer slot(String name) {
        return nameToSlot.get(name);
    }
}
