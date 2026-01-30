// Hierarchy-based policies for testing
// Tests: in operator with parent relationships

// Users in a group can read resources owned by that group
permit(
    principal,
    action == Test::Action::"read",
    resource
)
when {
    resource has ownerGroup && principal in resource.ownerGroup
};

// Organization admins can do anything to org resources
permit(
    principal,
    action,
    resource
)
when {
    principal has adminOf && resource in principal.adminOf
};

// Document inherits from Folder - if you can read the folder, you can read the document
permit(
    principal,
    action == Test::Action::"read",
    resource
)
when {
    // Document has a parent Folder
    resource in Test::Folder::"shared-folder"
};
