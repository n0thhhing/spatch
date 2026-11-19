package spatch;

import io.reactivex.rxjava3.disposables.Disposable;
import java.util.concurrent.atomic.AtomicInteger;

/** Regression checks for completion/cancellation before and after Rx returns its Disposable. */
public final class SubscriptionsTest {
    public static void main(String[] args) throws Exception {
        Subscriptions streams = new Subscriptions();
        AtomicInteger disposed = new AtomicInteger();
        Disposable source = () -> disposed.incrementAndGet();
        Subscriptions.Slot synchronous = streams.open(41);
        streams.cancel(41);
        synchronous.set(source);
        assert disposed.get() == 1 && streams.size() == 0 : "Synchronous completion must dispose the late source";
        Subscriptions.Slot asynchronous = streams.open(42);
        asynchronous.set(source);
        streams.cancel(42);
        streams.cancel(42);
        assert disposed.get() == 2 && streams.size() == 0 : "Cancellation is idempotent";
        for (int n = 0; n < 100; n++) {
            long token = n + 100;
            Subscriptions.Slot racing = streams.open(token);
            Thread cancel = new Thread(() -> streams.cancel(token));
            cancel.start();
            racing.set(source);
            cancel.join();
        }
        assert disposed.get() == 102 && streams.size() == 0 : "Assignment and cancellation cannot orphan a source";
        streams.open(1).set(source);
        streams.open(2).set(source);
        streams.clear();
        assert disposed.get() == 104 && streams.size() == 0 : "Account changes release all streams";
        System.out.println("SubscriptionsTest: all checks passed");
    }
}
