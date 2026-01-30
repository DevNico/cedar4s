// Ownership-based policies for benchmarking
// Tests: owner attribute comparison, entity references

// Owner has full access
permit(
    principal,
    action in [Bench::Action::"read", Bench::Action::"write", Bench::Action::"delete"],
    resource
)
when {
    resource has owner && resource.owner == principal
};

// Editors can read and write
permit(
    principal,
    action in [Bench::Action::"read", Bench::Action::"write"],
    resource
)
when {
    resource has editors && principal in resource.editors
};

// Viewers can only read
permit(
    principal,
    action == Bench::Action::"read",
    resource
)
when {
    resource has viewers && principal in resource.viewers
};
