// Ownership-based policies for testing
// Tests: owner attribute comparison, entity references

// Owner has full access
permit(
    principal,
    action in [Test::Action::"read", Test::Action::"write", Test::Action::"delete"],
    resource
)
when {
    resource has owner && resource.owner == principal
};

// Editors can read and write
permit(
    principal,
    action in [Test::Action::"read", Test::Action::"write"],
    resource
)
when {
    resource has editors && principal in resource.editors
};

// Viewers can only read
permit(
    principal,
    action == Test::Action::"read",
    resource
)
when {
    resource has viewers && principal in resource.viewers
};

// Admin role has full access to everything
permit(
    principal,
    action,
    resource
)
when {
    principal has role && principal.role == "admin"
};
