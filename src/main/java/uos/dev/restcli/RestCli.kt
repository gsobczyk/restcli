package uos.dev.restcli

import com.github.ajalt.mordant.TermColors
import com.jakewharton.picnic.table
import okhttp3.logging.HttpLoggingInterceptor
import picocli.CommandLine
import uos.dev.restcli.executor.OkhttpRequestExecutor
import uos.dev.restcli.jsbridge.JsClient
import uos.dev.restcli.parser.EnvironmentVariableInjector
import uos.dev.restcli.parser.EnvironmentVariableInjectorImpl
import uos.dev.restcli.parser.Parser
import uos.dev.restcli.parser.Request
import uos.dev.restcli.report.AsciiArtTestReportGenerator
import uos.dev.restcli.report.JunitTestReportGenerator
import uos.dev.restcli.report.TestGroupReport
import uos.dev.restcli.report.TestReportGenerator
import uos.dev.restcli.report.TestReportStore
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "restcli", version = ["Intellij RestCli v1.3"],
    mixinStandardHelpOptions = true,
    description = ["@|bold Intellij Restcli|@"]
)
class RestCli : Callable<Unit> {
    @CommandLine.Option(
        names = ["-e", "--env"],
        description = [
            "Name of the environment in config file ",
            "(http-client.env.json/http-client.private.env.json)."
        ]
    )
    var environmentName: String? = null

    @CommandLine.Option(
        names = ["-s", "--script"],
        description = ["Path to the http script file."],
        required = true
    )
    lateinit var httpFilePath: String

    @CommandLine.Option(
        names = ["-l", "--log-level"],
        description = [
            "Config log level while the executor running. ",
            "Valid values: \${COMPLETION-CANDIDATES}"
        ]
    )
    var logLevel: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.BODY

    @CommandLine.Option(
        names = ["-r", "--report"],
        description = ["The name of the test report such as \"test_report\"."]
    )
    var testReportName: String? = null

    private val t: TermColors = TermColors()
    private val testReportGenerator: TestReportGenerator = JunitTestReportGenerator()
    private val environmentVariableInjector: EnvironmentVariableInjector =
        EnvironmentVariableInjectorImpl()

    override fun call() {
        showInfo()

        println("Test file: $httpFilePath")

        val parser = Parser()
        val jsClient = JsClient()
        val environment = (environmentName?.let { EnvironmentLoader().load(it) } ?: emptyMap())
            .toMutableMap()

        val requests = parser.parse(FileReader(httpFilePath))

        val executor = OkhttpRequestExecutor(logLevel)
        TestReportStore.clear()
        requests.forEach { rawRequest ->
            runSafe {
                val jsGlobalEnv = jsClient.globalEnvironment()
                val request = injectEnv(rawRequest, environment, jsGlobalEnv)
                TestReportStore.addTestGroupReport(request.requestTarget)
                log("\n__________________________________________________\n")
                log(t.bold("##### ${request.method.name} ${request.requestTarget} #####"))
                val response = executor.execute(request)
                jsClient.updateResponse(response)
                request.scriptHandler?.let { script ->
                    val testTitle = t.bold("TESTS:")
                    log("\n$testTitle")
                    jsClient.execute(script)
                }
            }
        }
        log("\n__________________________________________________\n")
        val testGroupReports = TestReportStore.testGroupReports
        PrintWriter(System.out).use { AsciiArtTestReportGenerator().generate(testGroupReports, it) }
        generateTestReport(testGroupReports)
    }

    private fun injectEnv(
        request: Request,
        environment: Map<String, String>,
        jsGlobalEnv: Map<String, String>
    ): Request {
        fun inject(source: String): String =
            environmentVariableInjector.inject(source, jsGlobalEnv, environment)

        fun inject(headers: Map<String, String>): Map<String, String> {
            val result = mutableMapOf<String, String>()
            headers.forEach { (key, value) -> result[inject(key)] = inject(value) }
            return result
        }

        fun inject(part: Request.Part): Request.Part = part.copy(
            headers = inject(part.headers),
            body = part.body?.let(::inject)
        )

        return request.copy(
            requestTarget = inject(request.requestTarget),
            headers = inject(request.headers),
            body = request.body?.let(::inject),
            parts = request.parts.map(::inject)
        )
    }

    private fun generateTestReport(testGroupReports: List<TestGroupReport>) {
        val reportName = testReportName ?: return
        val reportDirectory = File("test-reports")
        reportDirectory.mkdirs()
        val reportFile = File(reportDirectory, "$reportName.xml")
        val writer = FileWriter(reportFile)
        writer.use { testReportGenerator.generate(testGroupReports, it) }
    }

    private fun showInfo() {
        val content = table {
            style {
                border = true
            }
            header {
                cellStyle {
                    border = true
                }
                row {
                    cell("restcli v1.3") {
                        columnSpan = 2
                    }
                }
                row {
                    cell("Environment name")
                    cell(environmentName)
                }
            }
        }
        println(content)
    }

    private fun runSafe(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            log(e.message.orEmpty())
            e.printStackTrace()
        }
    }

    private fun log(message: String) = println(message)
}
