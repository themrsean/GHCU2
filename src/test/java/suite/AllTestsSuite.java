package suite;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectPackages({
        "app",
        "model",
        "persistence",
        "service",
        "ui",
        "util"
})
public class AllTestsSuite {
}
