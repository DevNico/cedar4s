// PhotoFlash Authorization Policies
// Demonstrates advanced Cedar features with nested context and entity references

// =============================================================================
// Photo Viewing Policies
// =============================================================================

// Users can view their own photos
permit(
    principal,
    action == PhotoFlash::Action::"Photo::View",
    resource
)
when {
    resource has account &&
    resource.account has owner &&
    resource.account.owner == principal
};

// Users can view public photos in their account
permit(
    principal,
    action == PhotoFlash::Action::"Photo::View",
    resource
)
when {
    resource has account &&
    resource.account has owner &&
    resource.account.owner == principal &&
    resource has private &&
    !resource.private
};

// Account admins can view all photos in their account
permit(
    principal,
    action == PhotoFlash::Action::"Photo::View",
    resource
)
when {
    resource has account &&
    resource.account has admins &&
    principal in resource.account.admins
};

// Users can view public photos (regardless of account)
permit(
    principal,
    action == PhotoFlash::Action::"Photo::View",
    resource
)
when {
    resource has private && !resource.private &&
    context.authenticated == true
};

// =============================================================================
// Photo Upload Policies
// =============================================================================

// Account owners can upload photos to their albums
permit(
    principal,
    action == PhotoFlash::Action::"Album::UploadPhoto",
    resource
)
when {
    resource has account &&
    resource.account has owner &&
    resource.account.owner == principal &&
    context.authenticated == true &&
    context.photo_file_size < 10485760  // 10MB limit
};

// Account admins can upload photos
permit(
    principal,
    action == PhotoFlash::Action::"Album::UploadPhoto",
    resource
)
when {
    resource has account &&
    resource.account has admins &&
    principal in resource.account.admins &&
    context.authenticated == true &&
    context.photo.file_size < 10485760
};

// Forbid uploads of executable files
forbid(
    principal,
    action == PhotoFlash::Action::"Album::UploadPhoto",
    resource
)
when {
    context.photo_file_type == "application/x-executable" ||
    context.photo_file_type == "application/x-msdownload"
};

// =============================================================================
// Album Listing Policies
// =============================================================================

// Account owners can list all albums
permit(
    principal,
    action == PhotoFlash::Action::"Account::ListAlbums",
    resource
)
when {
    resource has owner && resource.owner == principal &&
    context.authenticated == true
};

// Account admins can list all albums
permit(
    principal,
    action == PhotoFlash::Action::"Account::ListAlbums",
    resource
)
when {
    resource has admins && principal in resource.admins &&
    context.authenticated == true
};

// =============================================================================
// Private Album Protection
// =============================================================================

// Deny viewing photos from private albums unless user is owner or admin
forbid(
    principal,
    action == PhotoFlash::Action::"Photo::View",
    resource
)
when {
    resource has private && resource.private &&
    resource has account &&
    resource.account has owner &&
    resource.account.owner != principal &&
    !(resource.account has admins && principal in resource.account.admins)
};
