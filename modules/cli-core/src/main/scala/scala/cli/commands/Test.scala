package scala.cli.commands

import caseapp._

import scala.build.{Build, Inputs, Os, Runner}
import scala.build.internal.Constants

object Test extends ScalaCommand[TestOptions] {
  override def group = "Main"
  def run(options: TestOptions, args: RemainingArgs): Unit = {

    val pwd = Os.pwd

    val inputs = Inputs(args.all, pwd, options.shared.directories.directories, defaultInputs = Some(Inputs.default())) match {
      case Left(message) =>
        System.err.println(message)
        sys.exit(1)
      case Right(i) => i
    }

    val buildOptions = options.buildOptions.copy(
      addTestRunnerDependencyOpt = Some(true)
    )
    val bloopgunConfig = options.shared.bloopgunConfig()

    if (options.shared.watch) {
      val watcher = Build.watch(inputs, buildOptions, bloopgunConfig, options.shared.logger, pwd, postAction = () => WatchUtil.printWatchMessage()) {
        case s: Build.Successful =>
          testOnce(options, inputs.workspace, inputs.projectName, s, allowExecve = false, exitOnError = false)
        case f: Build.Failed =>
          System.err.println("Compilation failed")
      }
      try WatchUtil.waitForCtrlC()
      finally watcher.dispose()
    } else {
      val build = Build.build(inputs, buildOptions, bloopgunConfig, options.shared.logger, pwd)
      build match {
        case s: Build.Successful =>
          testOnce(options, inputs.workspace, inputs.projectName, s, allowExecve = true, exitOnError = true)
        case f: Build.Failed =>
          System.err.println("Compilation failed")
          sys.exit(1)
      }
    }
  }

  private def testOnce(
    options: TestOptions,
    root: os.Path,
    projectName: String,
    build: Build.Successful,
    allowExecve: Boolean,
    exitOnError: Boolean
  ): Unit = {

    val retCode =
      if (options.shared.js.js)
        Run.withLinkedJs(build, None, addTestInitializer = true, options.shared.js.config) { js =>
          Runner.testJs(
            build.fullClassPath,
            js.toIO
          )
        }
      else if (options.shared.native.native)
        Run.withNativeLauncher(
          build,
          "scala.scalanative.testinterface.TestMain",
          options.shared.native.config,
          options.shared.nativeWorkDir(root, projectName),
          options.shared.scalaNativeLogger
        ) { launcher =>
          Runner.testNative(
            build.fullClassPath,
            launcher.toIO,
            options.shared.logger,
            options.shared.scalaNativeLogger
          )
        }
      else
        Runner.run(
          options.shared.javaCommand(),
          options.sharedJava.allJavaOpts,
          build.fullClassPath.map(_.toFile),
          Constants.testRunnerMainClass,
          Nil,
          options.shared.logger,
          allowExecve = allowExecve
        )

    if (retCode != 0) {
      if (exitOnError)
        sys.exit(retCode)
      else {
        val red = Console.RED
        val lightRed = "\u001b[91m"
        val reset = Console.RESET
        System.err.println(s"${red}Program exited with return code $lightRed$retCode$red.$reset")
      }
    }
  }
}
