package net.verdagon.vale.compileserver

import com.google.cloud.functions.{HttpFunction, HttpRequest, HttpResponse}
import net.verdagon.vale.{Err, Ok}
import net.verdagon.vale.driver.Driver
import net.verdagon.vale.driver.Driver.{Options, SourceInput, build, jsonifyProgram}

class BuildAction extends HttpFunction {
  override def service(request: HttpRequest, response: HttpResponse): Unit = {
    val code = scala.io.Source.fromInputStream(request.getInputStream).mkString
    if (code.isEmpty) {
      response.setStatusCode(400)
      response.getWriter.write("To compile a Vale program, specify the Vale code in the request body.\nExample: exported func main() int { ret 42; }\n")
      return
    }

    val options =
      Options(
        Vector(SourceInput(Driver.DEFAULT_PACKAGE_COORD, "in.vale", code)),
        Some(""),
        false, false, true, false, true, None, false, true, true, true)
    val json =
      Driver.build(options) match {
        case Ok(Some(programH)) => jsonifyProgram(programH)
        case Err(error) => {
          response.setStatusCode(400)
          response.getWriter.write(error)
          return
        }
      }

    response.getWriter.write(json + "\n")
  }
}
