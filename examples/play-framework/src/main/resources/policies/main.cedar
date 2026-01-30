permit (
  principal,
  action == PlayAuth::Action::"Document::Read",
  resource
) when {
  resource.owner == principal
};

permit (
  principal,
  action == PlayAuth::Action::"Document::Read",
  resource
) when {
  resource.visibility == "public"
};

permit (
  principal,
  action == PlayAuth::Action::"Document::Read",
  resource
) when {
  resource in principal.orgs
};

permit (
  principal,
  action == PlayAuth::Action::"Document::Write",
  resource
) when {
  resource.owner == principal
};

permit (
  principal,
  action == PlayAuth::Action::"Document::Create",
  resource
) when {
  resource in principal.orgs
};
