package spatch;

import io.reactivex.rxjava3.disposables.Disposable;
import java.util.concurrent.ConcurrentHashMap;

/** Owns subscriptions even when an Rx source terminates inside subscribe(). */
final class Subscriptions {
    private final ConcurrentHashMap<Long, Slot> slots = new ConcurrentHashMap<>();

    Slot open(long token) {
        Slot slot = new Slot();
        Slot old = slots.put(token, slot);
        if (old != null) old.dispose();
        return slot;
    }

    void cancel(long token) {
        Slot slot = slots.remove(token);
        if (slot != null) slot.dispose();
    }

    void clear() {
        for (Long token : slots.keySet()) cancel(token);
    }

    int size() { return slots.size(); }

    static final class Slot {
        private Disposable disposable;
        private boolean closed;

        synchronized void set(Disposable next) {
            if (closed) next.dispose();
            else disposable = next;
        }

        synchronized void dispose() {
            closed = true;
            if (disposable != null) {
                disposable.dispose();
                disposable = null;
            }
        }
    }
}
