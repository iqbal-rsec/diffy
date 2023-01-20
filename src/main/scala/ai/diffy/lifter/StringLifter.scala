package ai.diffy.lifter

import scala.util.Try

object StringLifter {
  val htmlRegexPattern = """<("[^"]*"|'[^']*'|[^'">])*>""".r

  def lift(string: String): Any = {
    if(string == null) null else
    Try(new FieldMap(Map("type" -> "json", "value" -> JsonLifter.lift(JsonLifter.decode(string))))).getOrElse {
      string
    }
  }
}
