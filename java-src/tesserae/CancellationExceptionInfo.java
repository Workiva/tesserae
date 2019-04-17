// Copyright 2017-2019 Workiva Inc.
// 
// Licensed under the Eclipse Public License 1.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// 
//      http://opensource.org/licenses/eclipse-1.0.php
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

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
