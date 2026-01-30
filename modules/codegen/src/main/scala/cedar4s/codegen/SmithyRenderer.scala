package cedar4s.codegen

import java.nio.file.{Path, Paths}

/** Renders Cedar IR to Smithy enum files.
  *
  * Generates per-domain action enums that can be used in Smithy API definitions:
  *   - Action enum (e.g., MissionAction)
  *   - Allowed actions list (e.g., MissionAllowedActions)
  *   - Capabilities mixin (e.g., MissionCapabilitiesMixin)
  *
  * These enums enable type-safe capability responses in smithy4s-generated APIs.
  *
  * ==Generated Files==
  *
  * For each domain with actions, generates:
  *   - `{Domain}Action.smithy` - Enum, list, and mixin for that domain
  *
  * ==Example Output==
  *
  * {{{
  * $version: "2.0"
  *
  * namespace com.example.api.authz
  *
  * /// Actions available for the Mission domain.
  * enum MissionAction {
  *     /// Create a new mission
  *     CREATE = "create"
  *
  *     /// View mission details
  *     READ = "read"
  *
  *     /// Modify mission
  *     UPDATE = "update"
  * }
  *
  * /// List of allowed Mission actions for capability responses
  * list MissionAllowedActions {
  *     member: MissionAction
  * }
  *
  * /// Mixin to add Mission capabilities to a structure.
  * @mixin
  * structure MissionCapabilitiesMixin {
  *     @required
  *     allowedActions: MissionAllowedActions
  * }
  * }}}
  *
  * ==Usage in Smithy==
  *
  * Apply the mixin to add capabilities to existing structures:
  *
  * {{{
  * use com.example.api.authz#MissionCapabilitiesMixin
  *
  * structure Mission with [MissionCapabilitiesMixin] {
  *     @required
  *     id: String
  *     @required
  *     name: String
  * }
  * }}}
  */
object SmithyRenderer {

  /** Render Cedar IR to Smithy enum files.
    *
    * @param ir
    *   The Cedar IR with actions to generate
    * @param namespace
    *   Smithy namespace for generated files
    * @return
    *   Map of relative file paths to Smithy content
    */
  def render(ir: CedarIR, namespace: String): Map[Path, String] = {
    // Group actions by domain
    val actionsByDomain = ir.actionsByDomain

    // Generate a Smithy file for each domain
    actionsByDomain.flatMap { case (domainName, actions) =>
      if (actions.isEmpty) {
        None
      } else {
        val fileName = Paths.get(s"${domainName}Action.smithy")
        val content = renderDomainEnum(domainName, actions, namespace, ir)
        Some(fileName -> content)
      }
    }
  }

  /** Render a single domain's action enum.
    */
  private def renderDomainEnum(
      domainName: String,
      actions: List[ActionIR],
      namespace: String,
      ir: CedarIR
  ): String = {
    // Generate enum members
    val enumMembers = actions
      .sortBy(_.name)
      .map { action =>
        val enumName = actionNameToEnumCase(action.name)
        val doc = action.doc.map(d => s"    /// $d").getOrElse("")

        if (doc.nonEmpty) {
          s"""$doc
           |    $enumName = "${action.name}"""".stripMargin
        } else {
          s"""    $enumName = "${action.name}""""
        }
      }
      .mkString("\n\n")

    // Get entity for this domain (if exists)
    val entityDoc = ir.entitiesByName.get(domainName).flatMap(_.doc)
    val domainDoc = entityDoc.getOrElse(s"Actions for the $domainName domain")

    s"""$$version: "2.0"
       |
       |namespace $namespace
       |
       |/// Actions available for the $domainName domain.
       |/// $domainDoc
       |enum ${domainName}Action {
       |$enumMembers
       |}
       |
       |/// List of allowed $domainName actions for capability responses
       |list ${domainName}AllowedActions {
       |    member: ${domainName}Action
       |}
       |
       |/// Mixin to add $domainName capabilities to a structure.
       |/// Apply this mixin to include allowedActions in your API response types.
       |@mixin
       |structure ${domainName}CapabilitiesMixin {
       |    /// Actions the current user is allowed to perform on this $domainName
       |    @required
       |    allowedActions: ${domainName}AllowedActions
       |}
       |""".stripMargin
  }

  /** Convert an action name to Smithy enum case.
    *
    * Examples:
    *   - "create" -> "CREATE"
    *   - "read-file" -> "READ_FILE"
    *   - "list_all" -> "LIST_ALL"
    */
  private def actionNameToEnumCase(name: String): String = {
    name
      .replace("-", "_")
      .replace(".", "_")
      .toUpperCase
  }
}
