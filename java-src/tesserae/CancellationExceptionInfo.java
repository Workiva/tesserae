package tesserae;

import java.util.concurrent.CancellationException;
import clojure.lang.IExceptionInfo;
import clojure.lang.PersistentArrayMap;
import clojure.lang.IPersistentMap;

public class CancellationExceptionInfo extends CancellationException
        implements IExceptionInfo {

    private IPersistentMap data = PersistentArrayMap.EMPTY;

    public CancellationExceptionInfo() {
        super();
    }

    public CancellationExceptionInfo(String message) {
        super(message);
    }

    public CancellationExceptionInfo(String message, IPersistentMap data) {
        super(message);
        this.data = data;
    }

    public CancellationExceptionInfo(IPersistentMap data) {
        super();
        this.data = data;
    }

    public IPersistentMap getData() {
        return data;
    }
}
