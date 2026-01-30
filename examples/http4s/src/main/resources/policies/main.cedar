permit (
  principal,
  action == Http4sAuth::Action::"Document::Read",
  resource
) when {
  resource.owner == principal
};
