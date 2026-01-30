package models

final case class User(id: String, email: String)
final case class Org(id: String, name: String)
final case class Membership(userId: String, orgId: String)
final case class Document(
    id: String,
    orgId: String,
    ownerId: String,
    title: String,
    visibility: String
)
