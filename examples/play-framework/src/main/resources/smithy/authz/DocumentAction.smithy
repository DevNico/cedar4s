$version: "2.0"

namespace example.playframework.authz

/// Actions available for the Document domain.
/// Actions for the Document domain
enum DocumentAction {
    CREATE = "Create"

    READ = "Read"

    WRITE = "Write"
}

/// List of allowed Document actions for capability responses
list DocumentAllowedActions {
    member: DocumentAction
}

/// Mixin to add Document capabilities to a structure.
/// Apply this mixin to include allowedActions in your API response types.
@mixin
structure DocumentCapabilitiesMixin {
    /// Actions the current user is allowed to perform on this Document
    @required
    allowedActions: DocumentAllowedActions
}
