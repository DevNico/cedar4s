// GitHub RBAC Policies
// Demonstrates team-based permissions, repository visibility, and branch protection

// =============================================================================
// Organization Policies
// =============================================================================

// Org admins have full access to the organization
permit(
    principal,
    action in [GitHub::Action::"Organization::view", GitHub::Action::"Organization::update",
               GitHub::Action::"Organization::manageMembers", GitHub::Action::"Organization::manageTeams",
               GitHub::Action::"Organization::createRepository"],
    resource
)
when {
    resource has admins && principal in resource.admins
};

// Org members can view the organization
permit(
    principal,
    action == GitHub::Action::"Organization::view",
    resource
)
when {
    resource has members && principal in resource.members
};

// Org members can create repositories (if allowed by org settings)
permit(
    principal,
    action == GitHub::Action::"Organization::createRepository",
    resource
)
when {
    resource has members && principal in resource.members &&
    resource has membersCanCreateRepos && resource.membersCanCreateRepos
};

// =============================================================================
// Repository Policies
// =============================================================================

// Public repositories can be viewed by anyone
permit(
    principal,
    action in [GitHub::Action::"Repository::view", GitHub::Action::"Repository::clone",
               GitHub::Action::"Repository::fork"],
    resource
)
when {
    resource has visibility && resource.visibility == "public"
};

// Repository admins have full access
permit(
    principal,
    action in [GitHub::Action::"Repository::view", GitHub::Action::"Repository::clone",
               GitHub::Action::"Repository::push", GitHub::Action::"Repository::admin",
               GitHub::Action::"Repository::delete", GitHub::Action::"Repository::fork",
               GitHub::Action::"Repository::manageSettings"],
    resource
)
when {
    resource has admins && principal in resource.admins
};

// Repository writers can view, clone, push, and fork
permit(
    principal,
    action in [GitHub::Action::"Repository::view", GitHub::Action::"Repository::clone",
               GitHub::Action::"Repository::push", GitHub::Action::"Repository::fork"],
    resource
)
when {
    resource has writers && principal in resource.writers
};

// Repository readers can view, clone, and fork
permit(
    principal,
    action in [GitHub::Action::"Repository::view", GitHub::Action::"Repository::clone",
               GitHub::Action::"Repository::fork"],
    resource
)
when {
    resource has readers && principal in resource.readers
};

// =============================================================================
// Branch Policies
// =============================================================================

// Anyone who can view the repo can view branches
permit(
    principal,
    action == GitHub::Action::"Branch::view",
    resource
)
when {
    true  // Inherits from repository permissions (checked via parent)
};

// Writers can push to unprotected branches
permit(
    principal,
    action == GitHub::Action::"Branch::push",
    resource
)
when {
    resource has isProtected && !resource.isProtected &&
    resource has writers && principal in resource.writers
};

// Admins can push to any branch (including protected)
permit(
    principal,
    action == GitHub::Action::"Branch::push",
    resource
)
when {
    resource has admins && principal in resource.admins
};

// Writers can delete unprotected branches
permit(
    principal,
    action == GitHub::Action::"Branch::delete",
    resource
)
when {
    resource has isProtected && !resource.isProtected &&
    resource has writers && principal in resource.writers
};

// Only admins can protect/unprotect branches
permit(
    principal,
    action == GitHub::Action::"Branch::protect",
    resource
)
when {
    resource has admins && principal in resource.admins
};

// =============================================================================
// Pull Request Policies
// =============================================================================

// Anyone who can view the repo can view PRs
permit(
    principal,
    action == GitHub::Action::"PullRequest::view",
    resource
)
when {
    true  // Inherits from repository permissions
};

// PR authors can update their own PRs
permit(
    principal,
    action == GitHub::Action::"PullRequest::update",
    resource
)
when {
    resource has author && resource.author == principal
};

// Writers can update any PR
permit(
    principal,
    action == GitHub::Action::"PullRequest::update",
    resource
)
when {
    resource has writers && principal in resource.writers
};

// Writers can merge and close PRs
permit(
    principal,
    action in [GitHub::Action::"PullRequest::merge", GitHub::Action::"PullRequest::close"],
    resource
)
when {
    resource has writers && principal in resource.writers
};

// Anyone who can view can review PRs
permit(
    principal,
    action == GitHub::Action::"PullRequest::review",
    resource
)
when {
    resource has readers && principal in resource.readers
};

// PR authors can close their own PRs
permit(
    principal,
    action == GitHub::Action::"PullRequest::close",
    resource
)
when {
    resource has author && resource.author == principal
};
