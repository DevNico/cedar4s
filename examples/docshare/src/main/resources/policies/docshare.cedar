// Document Sharing Policies
// Demonstrates ReBAC patterns: owner/editor/viewer relationships

// =============================================================================
// Folder Policies
// =============================================================================

// Owner has full access to folders
permit(
    principal,
    action in [DocShare::Action::"Folder::view", DocShare::Action::"Folder::edit", 
               DocShare::Action::"Folder::delete", DocShare::Action::"Folder::share"],
    resource
)
when {
    resource has owner && resource.owner == principal
};

// Editors can view and edit folders
permit(
    principal,
    action in [DocShare::Action::"Folder::view", DocShare::Action::"Folder::edit"],
    resource
)
when {
    resource has editors && principal in resource.editors
};

// Viewers can view folders
permit(
    principal,
    action == DocShare::Action::"Folder::view",
    resource
)
when {
    resource has viewers && principal in resource.viewers
};

// Group members inherit folder viewer access
permit(
    principal,
    action == DocShare::Action::"Folder::view",
    resource
)
when {
    resource has viewerGroups && 
    resource.viewerGroups.containsAny([principal])
};

// =============================================================================
// Document Policies
// =============================================================================

// Owner has full access to documents
permit(
    principal,
    action in [DocShare::Action::"Document::view", DocShare::Action::"Document::edit",
               DocShare::Action::"Document::delete", DocShare::Action::"Document::share",
               DocShare::Action::"Document::comment"],
    resource
)
when {
    resource has owner && resource.owner == principal
};

// Editors can view, edit, and comment on documents (unless locked)
permit(
    principal,
    action in [DocShare::Action::"Document::view", DocShare::Action::"Document::comment"],
    resource
)
when {
    resource has editors && principal in resource.editors
};

permit(
    principal,
    action == DocShare::Action::"Document::edit",
    resource
)
when {
    resource has editors && principal in resource.editors &&
    resource has locked && !resource.locked
};

// Viewers can view and comment on documents
permit(
    principal,
    action in [DocShare::Action::"Document::view", DocShare::Action::"Document::comment"],
    resource
)
when {
    resource has viewers && principal in resource.viewers
};

// =============================================================================
// Comment Policies
// =============================================================================

// Comment authors can edit and delete their own comments
permit(
    principal,
    action in [DocShare::Action::"Comment::view", DocShare::Action::"Comment::edit", 
               DocShare::Action::"Comment::delete"],
    resource
)
when {
    resource has author && resource.author == principal
};

// Anyone who can view a document can view its comments
// (This would need document context in practice - simplified here)
permit(
    principal,
    action == DocShare::Action::"Comment::view",
    resource
)
when {
    true  // In practice, check parent document permissions
};
