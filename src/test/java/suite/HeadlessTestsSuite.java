package suite;

import org.junit.platform.suite.api.Select;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectPackages({
        "app",
        "model",
        "persistence",
        "service",
        "util"
})
@Select("class:ui.MainWindowControllerTest")
@Select("class:ui.GradingWindowControllerTest")
@Select("class:ui.LogAppenderTest")
public class HeadlessTestsSuite {
}
