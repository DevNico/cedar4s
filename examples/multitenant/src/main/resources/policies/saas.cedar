// Multi-Tenant SaaS Policies
// Demonstrates strict tenant isolation and role-based access using permission sets
// Note: Using "resource has X" pattern instead of "resource is Type" to work without schema validation

// =============================================================================
// ORGANIZATION POLICIES
// =============================================================================

// Organization owners have full access to their org
permit(
    principal,
    action,
    resource
)
when {
    resource has owners && principal in resource.owners
};

// Organization admins can manage the org (except delete)
permit(
    principal,
    action in [
        SaaS::Action::"Organization::view",
        SaaS::Action::"Organization::update",
        SaaS::Action::"Organization::manageMembers",
        SaaS::Action::"Organization::createWorkspace"
    ],
    resource
)
when {
    resource has admins && principal in resource.admins
};

// Organization members can view and create workspaces
permit(
    principal,
    action in [
        SaaS::Action::"Organization::view",
        SaaS::Action::"Organization::createWorkspace"
    ],
    resource
)
when {
    resource has members && principal in resource.members
};

// Billing users can view org and manage billing
permit(
    principal,
    action in [
        SaaS::Action::"Organization::view",
        SaaS::Action::"Organization::manageBilling"
    ],
    resource
)
when {
    resource has billingUsers && principal in resource.billingUsers
};

// =============================================================================
// WORKSPACE POLICIES
// =============================================================================

// Workspace admins have full access
permit(
    principal,
    action in [
        SaaS::Action::"Workspace::view",
        SaaS::Action::"Workspace::update",
        SaaS::Action::"Workspace::delete",
        SaaS::Action::"Workspace::manageMembers",
        SaaS::Action::"Workspace::createProject"
    ],
    resource
)
when {
    resource has admins && principal in resource.admins
};

// Workspace members can view and create projects
permit(
    principal,
    action in [
        SaaS::Action::"Workspace::view",
        SaaS::Action::"Workspace::createProject"
    ],
    resource
)
when {
    resource has members && principal in resource.members
};

// Workspace viewers can only view
permit(
    principal,
    action == SaaS::Action::"Workspace::view",
    resource
)
when {
    resource has viewers && principal in resource.viewers
};

// =============================================================================
// PROJECT POLICIES
// =============================================================================

// Project admins have full access (unless archived)
permit(
    principal,
    action in [
        SaaS::Action::"Project::view",
        SaaS::Action::"Project::update",
        SaaS::Action::"Project::delete",
        SaaS::Action::"Project::archive",
        SaaS::Action::"Project::manageMembers",
        SaaS::Action::"Environment::create"
    ],
    resource
)
when {
    resource has admins && principal in resource.admins &&
    resource has archived && !resource.archived
};

// Project admins can still view archived projects
permit(
    principal,
    action == SaaS::Action::"Project::view",
    resource
)
when {
    resource has admins && principal in resource.admins
};

// Project maintainers can update and manage environments
permit(
    principal,
    action in [
        SaaS::Action::"Project::view",
        SaaS::Action::"Project::update",
        SaaS::Action::"Environment::create"
    ],
    resource
)
when {
    resource has maintainers && principal in resource.maintainers &&
    resource has archived && !resource.archived
};

// Project developers can view
permit(
    principal,
    action == SaaS::Action::"Project::view",
    resource
)
when {
    resource has developers && principal in resource.developers
};

// Project viewers can only view
permit(
    principal,
    action == SaaS::Action::"Project::view",
    resource
)
when {
    resource has viewers && principal in resource.viewers
};

// =============================================================================
// ENVIRONMENT POLICIES
// =============================================================================

// Environment admins can do everything
permit(
    principal,
    action in [
        SaaS::Action::"Environment::view",
        SaaS::Action::"Environment::update",
        SaaS::Action::"Environment::delete",
        SaaS::Action::"Secret::create",
        SaaS::Action::"Deployment::create"
    ],
    resource
)
when {
    resource has admins && principal in resource.admins
};

// Environment members can view and create (unprotected only)
permit(
    principal,
    action in [
        SaaS::Action::"Environment::view",
        SaaS::Action::"Secret::create",
        SaaS::Action::"Deployment::create"
    ],
    resource
)
when {
    resource has members && principal in resource.members &&
    resource has isProtected && !resource.isProtected
};

// Environment members can still view protected environments
permit(
    principal,
    action == SaaS::Action::"Environment::view",
    resource
)
when {
    resource has members && principal in resource.members
};

// Environment viewers can only view
permit(
    principal,
    action == SaaS::Action::"Environment::view",
    resource
)
when {
    resource has viewers && principal in resource.viewers
};

// =============================================================================
// SECRET POLICIES
// =============================================================================

// Secret admins have full access including viewing the value
permit(
    principal,
    action in [
        SaaS::Action::"Secret::view",
        SaaS::Action::"Secret::update",
        SaaS::Action::"Secret::delete",
        SaaS::Action::"Secret::viewValue"
    ],
    resource
)
when {
    resource has admins && principal in resource.admins
};

// Secret members can view metadata and update
permit(
    principal,
    action in [
        SaaS::Action::"Secret::view",
        SaaS::Action::"Secret::update"
    ],
    resource
)
when {
    resource has members && principal in resource.members
};

// Secret viewers can only view metadata (not the actual value)
permit(
    principal,
    action == SaaS::Action::"Secret::view",
    resource
)
when {
    resource has viewers && principal in resource.viewers
};

// =============================================================================
// DEPLOYMENT POLICIES
// =============================================================================

// Deployment admins can do everything
permit(
    principal,
    action in [
        SaaS::Action::"Deployment::view",
        SaaS::Action::"Deployment::rollback",
        SaaS::Action::"Deployment::promote"
    ],
    resource
)
when {
    resource has admins && principal in resource.admins
};

// Deployment members can view and promote
permit(
    principal,
    action in [
        SaaS::Action::"Deployment::view",
        SaaS::Action::"Deployment::promote"
    ],
    resource
)
when {
    resource has members && principal in resource.members
};

// Deployment viewers can only view
permit(
    principal,
    action == SaaS::Action::"Deployment::view",
    resource
)
when {
    resource has viewers && principal in resource.viewers
};
