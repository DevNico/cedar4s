// Hierarchy-based policies for benchmarking  
// Tests: in operator with parent relationships

// Users who are members of a group can access resources owned by that group
permit(
    principal,
    action == Bench::Action::"read",
    resource
)
when {
    resource in principal.groups
};

// Admins of an organization can access all resources in that org
permit(
    principal,
    action in [Bench::Action::"read", Bench::Action::"write", Bench::Action::"delete", Bench::Action::"admin"],
    resource
)
when {
    principal has adminOf && resource in principal.adminOf
};

// Resources inherit permissions from parent folders
permit(
    principal,
    action == Bench::Action::"read",
    resource
)
when {
    resource has folder && principal in resource.folder.viewers
};
