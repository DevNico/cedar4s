package cedar4s.schema

import munit.FunSuite

/** Comprehensive tests for the Cedar schema parser.
  */
class CedarSchemaParserSpec extends FunSuite {

  // ===========================================================================
  // Basic Entity Parsing
  // ===========================================================================

  test("Entity parsing: parses a simple entity with no attributes") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity User;
      }
    """)

    assertEquals(schema.allEntities.length, 1)
    val user = schema.allEntities.head
    assertEquals(user.name, "User")
    assert(user.memberOf.isEmpty)
    assertEquals(user.shape, None)
  }

  test("Entity parsing: parses entity with attributes") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity User = {
          "email": String,
          "age": Long,
          "active": Bool,
        };
      }
    """)

    val user = schema.findEntity("User").get
    val attrs = user.attributes
    assertEquals(attrs.length, 3)

    val email = user.attribute("email").get
    assertEquals(email.typeExpr, SchemaType.String)
    assertEquals(email.optional, false)

    val age = user.attribute("age").get
    assertEquals(age.typeExpr, SchemaType.Long)

    val active = user.attribute("active").get
    assertEquals(active.typeExpr, SchemaType.Bool)
  }

  test("Entity parsing: parses entity with optional attributes") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity User = {
          "name": String,
          "nickname"?: String,
        };
      }
    """)

    val user = schema.findEntity("User").get
    assertEquals(user.requiredAttributes.length, 1)
    assertEquals(user.optionalAttributes.length, 1)
    assertEquals(user.attribute("nickname").get.optional, true)
  }

  test("Entity parsing: parses entity with parent types") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Group;
        entity User in [Group] = {
          "email": String,
        };
      }
    """)

    val user = schema.findEntity("User").get
    assertEquals(user.memberOf.length, 1)
    assertEquals(user.memberOf.head.simple, "Group")
  }

  test("Entity parsing: parses entity with multiple parents") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Customer;
        entity Location in [Customer];
        entity Mission in [Location, Customer];
      }
    """)

    val mission = schema.findEntity("Mission").get
    assertEquals(mission.memberOf.length, 2)
    assert(mission.memberOf.map(_.simple).contains("Location"))
    assert(mission.memberOf.map(_.simple).contains("Customer"))
  }

  test("Entity parsing: parses entity with Set<T> attributes") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Role;
        entity User = {
          "roles": Set<Role>,
          "tags": Set<String>,
        };
      }
    """)

    val user = schema.findEntity("User").get
    val roles = user.attribute("roles").get.typeExpr
    assert(roles.isInstanceOf[SchemaType.SetOf])

    val tags = user.attribute("tags").get.typeExpr
    assertEquals(tags, SchemaType.SetOf(SchemaType.String))
  }

  test("Entity parsing: parses entity with entity reference attributes") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Customer;
        entity User = {
          "customer": Customer,
        };
      }
    """)

    val user = schema.findEntity("User").get
    val customerAttr = user.attribute("customer").get.typeExpr
    assert(customerAttr.isInstanceOf[SchemaType.EntityRef])
    assertEquals(customerAttr.asInstanceOf[SchemaType.EntityRef].name.simple, "Customer")
  }

  test("Entity parsing: parses enum entity") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Status enum ["draft", "published", "archived"];
      }
    """)

    val status = schema.findEntity("Status").get
    assertEquals(status.isEnum, true)
    assert(status.enumValues.get.contains("draft"))
    assert(status.enumValues.get.contains("published"))
    assert(status.enumValues.get.contains("archived"))
  }

  test("Entity parsing: parses entity with tags type") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Document tags String;
      }
    """)

    val doc = schema.findEntity("Document").get
    assertEquals(doc.tags, Some(SchemaType.String))
  }

  test("Entity parsing: rejects tags blocks (not part of Cedar spec)") {
    val result = CedarSchema.parse("""
      namespace Test {
        entity User = {
          "email": String,
        } tags {
          // Comment inside tags block
          Some content that should be ignored { nested { braces } }
        };
      }
    """)

    assert(result.isLeft)
  }

  // ===========================================================================
  // Action Parsing
  // ===========================================================================

  test("Action parsing: parses action with appliesTo") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity User;
        entity Document;
        
        action "read" appliesTo {
          principal: [User],
          resource: [Document],
        };
      }
    """)

    val action = schema.findAction("read").get
    assertEquals(action.name, "read")
    assertEquals(action.principalTypes.map(_.simple), List("User"))
    assertEquals(action.resourceTypes.map(_.simple), List("Document"))
  }

  test("Action parsing: parses action with domain prefix") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity User;
        entity Mission;
        
        action "Mission::create" appliesTo {
          principal: [User],
          resource: [Mission],
        };
      }
    """)

    val action = schema.findAction("Mission::create").get
    assertEquals(action.domain, Some("Mission"))
    assertEquals(action.actionName, "create")
  }

  test("Action parsing: parses action with multiple principal/resource types") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity User;
        entity Admin;
        entity Document;
        entity Folder;
        
        action "view" appliesTo {
          principal: [User, Admin],
          resource: [Document, Folder],
        };
      }
    """)

    val action = schema.findAction("view").get
    assertEquals(action.principalTypes.length, 2)
    assertEquals(action.resourceTypes.length, 2)
  }

  test("Action parsing: parses action with context") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity User;
        entity Document;
        
        action "update" appliesTo {
          principal: [User],
          resource: [Document],
          context: {
            "reason": String,
            "urgent"?: Bool,
          },
        };
      }
    """)

    val action = schema.findAction("update").get
    val context = action.contextType.get
    assertEquals(context.attributes.length, 2)
    assertEquals(context.get("reason").get.optional, false)
    assertEquals(context.get("urgent").get.optional, true)
  }

  test("Action parsing: parses action with parent actions") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        action "readOnly";
        action "read" in [readOnly];
      }
    """)

    val read = schema.findAction("read").get
    assertEquals(read.memberOf.length, 1)
    assertEquals(read.memberOf.head.name, "readOnly")
  }

  test("Action parsing: detects collection actions") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Location;
        entity Mission;
        
        action "Mission::create" appliesTo {
          principal: User,
          resource: [Location],
        };
        
        action "Mission::read" appliesTo {
          principal: User,
          resource: [Mission],
        };
        
        entity User;
      }
    """)

    val create = schema.findAction("Mission::create").get
    val read = schema.findAction("Mission::read").get

    assertEquals(create.isCollectionAction, true) // resource is Location, not Mission
    assertEquals(read.isCollectionAction, false) // resource is Mission
  }

  // ===========================================================================
  // Common Type Parsing
  // ===========================================================================

  test("Common type parsing: parses type alias to primitive") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        type Email = String;
      }
    """)

    assertEquals(schema.allCommonTypes.length, 1)
    val email = schema.allCommonTypes.head
    assertEquals(email.name, "Email")
    assertEquals(email.typeExpr, SchemaType.String)
  }

  test("Common type parsing: parses type alias to record") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        type Address = {
          "street": String,
          "city": String,
          "zip": String,
        };
      }
    """)

    val addr = schema.allCommonTypes.head
    assert(addr.typeExpr.isInstanceOf[SchemaType.Record])
    val record = addr.typeExpr.asInstanceOf[SchemaType.Record].record
    assertEquals(record.attributes.length, 3)
  }

  test("Common type parsing: resolves custom type references in attributes") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        type PersonName = {
          "first": String,
          "last": String,
        };
        
        entity User = {
          "name": PersonName,
          "aliases": Set<PersonName>,
        };
      }
    """)

    val user = schema.findEntity("User").get
    val nameAttr = user.attribute("name").get.typeExpr
    val aliasesAttr = user.attribute("aliases").get.typeExpr

    assertEquals(nameAttr, SchemaType.TypeRef(QualifiedName("PersonName")))
    assertEquals(aliasesAttr, SchemaType.SetOf(SchemaType.TypeRef(QualifiedName("PersonName"))))
  }

  // ===========================================================================
  // Namespace Parsing
  // ===========================================================================

  test("Namespace parsing: parses named namespace") {
    val schema = CedarSchema.parseUnsafe("""
      namespace MyApp {
        entity User;
      }
    """)

    assertEquals(schema.namespaces.length, 1)
    assertEquals(schema.namespaces.head.name.get.value, "MyApp")
  }

  test("Namespace parsing: parses qualified namespace name") {
    val schema = CedarSchema.parseUnsafe("""
      namespace MyOrg::MyApp {
        entity User;
      }
    """)

    val nsName = schema.namespaces.head.name.get
    assertEquals(nsName.value, "MyOrg::MyApp")
    assertEquals(nsName.parts, List("MyOrg", "MyApp"))
  }

  test("Namespace parsing: parses multiple namespaces") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Auth {
        entity User;
      }
      
      namespace Docs {
        entity Document;
      }
    """)

    assertEquals(schema.namespaces.length, 2)
    assertEquals(schema.allEntities.length, 2)
  }

  // ===========================================================================
  // Annotation Parsing
  // ===========================================================================

  test("Annotation parsing: parses @doc annotation on entity") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        @doc("A user in the system")
        entity User;
      }
    """)

    val user = schema.findEntity("User").get
    assertEquals(user.doc, Some("A user in the system"))
  }

  test("Annotation parsing: parses custom annotations") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        @doc("User entity")
        @category("principal")
        entity User;
      }
    """)

    val user = schema.findEntity("User").get
    assertEquals(user.annotation("category"), Some("principal"))
    assertEquals(user.hasAnnotation("category"), true)
    assertEquals(user.hasAnnotation("nonexistent"), false)
  }

  test("Annotation parsing: parses annotations on attributes") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity User = {
          @doc("User's email address")
          "email": String,
        };
      }
    """)

    val email = schema.findEntity("User").get.attribute("email").get
    assertEquals(email.doc, Some("User's email address"))
  }

  test("Annotation parsing: parses annotations on namespaces") {
    val schema = CedarSchema.parseUnsafe("""
      @doc("Main namespace")
      namespace MyApp {
        entity User;
      }
    """)

    assertEquals(schema.namespaces.head.doc, Some("Main namespace"))
  }

  test("Annotation parsing: parses annotations on actions") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        @doc("Read a document")
        action "read";
      }
    """)

    assertEquals(schema.findAction("read").get.doc, Some("Read a document"))
  }

  test("Annotation parsing: parses @refinement annotation on entity") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        @refinement("com.example.ids.CustomerId")
        entity Customer;
      }
    """)

    val customer = schema.findEntity("Customer").get
    assertEquals(customer.annotation("refinement"), Some("com.example.ids.CustomerId"))
  }

  test("Annotation parsing: rejects annotations with multiple values (not part of Cedar spec)") {
    val result = CedarSchema.parse("""
      namespace Test {
        @custom("value1", "value2", "value3")
        entity Foo;
      }
    """)

    assert(result.isLeft)
  }

  test("Annotation parsing: parses multiple annotations including @refinement") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        @doc("A customer organization")
        @refinement("com.example.ids.CustomerId")
        entity Customer = {
          "name": String,
        };
      }
    """)

    val customer = schema.findEntity("Customer").get
    assertEquals(customer.doc, Some("A customer organization"))
    assertEquals(customer.annotation("refinement"), Some("com.example.ids.CustomerId"))
    assertEquals(customer.attributes.length, 1)
  }

  // ===========================================================================
  // Entity Hierarchy
  // ===========================================================================

  test("Entity hierarchy: identifies root entities") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Customer;
        entity User;
        entity Location in [Customer];
      }
    """)

    val hierarchy = schema.entityHierarchy
    assert(hierarchy.roots.contains("Customer"))
    assert(hierarchy.roots.contains("User"))
    assert(!hierarchy.roots.contains("Location"))
  }

  test("Entity hierarchy: identifies leaf entities") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Customer;
        entity Location in [Customer];
        entity Mission in [Location];
      }
    """)

    val hierarchy = schema.entityHierarchy
    assert(hierarchy.leaves.contains("Mission"))
    assert(!hierarchy.leaves.contains("Customer"))
    assert(!hierarchy.leaves.contains("Location"))
  }

  test("Entity hierarchy: computes entity depth") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Customer;
        entity Location in [Customer];
        entity Mission in [Location];
        entity MissionRun in [Mission];
      }
    """)

    val hierarchy = schema.entityHierarchy
    assertEquals(hierarchy.depthOf("Customer"), 0)
    assertEquals(hierarchy.depthOf("Location"), 1)
    assertEquals(hierarchy.depthOf("Mission"), 2)
    assertEquals(hierarchy.depthOf("MissionRun"), 3)
    assertEquals(hierarchy.maxDepth, 3)
  }

  test("Entity hierarchy: finds ancestors and descendants") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Customer;
        entity Location in [Customer];
        entity Mission in [Location];
      }
    """)

    val hierarchy = schema.entityHierarchy
    assert(hierarchy.ancestorsOf("Mission").contains("Location"))
    assert(hierarchy.ancestorsOf("Mission").contains("Customer"))
    assert(hierarchy.descendantsOf("Customer").contains("Location"))
    assert(hierarchy.descendantsOf("Customer").contains("Mission"))
  }

  test("Entity hierarchy: finds paths") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Customer;
        entity Location in [Customer];
        entity Mission in [Location, Customer];
      }
    """)

    val hierarchy = schema.entityHierarchy
    val paths = hierarchy.pathsTo("Mission", "Customer")
    assertEquals(paths.length, 2) // Direct path and through Location
  }

  test("Entity hierarchy: handles multiple parents correctly") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Customer;
        entity Location in [Customer];
        entity Mission in [Location, Customer];
      }
    """)

    val hierarchy = schema.entityHierarchy
    assert(hierarchy.parentsOf("Mission").contains("Location"))
    assert(hierarchy.parentsOf("Mission").contains("Customer"))
    assert(hierarchy.childrenOf("Customer").contains("Location"))
    assert(hierarchy.childrenOf("Customer").contains("Mission"))
  }

  // ===========================================================================
  // Extension Types
  // ===========================================================================

  test("Extension types: parses ipaddr attribute") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Request = {
          "sourceIp": ipaddr,
        };
      }
    """)

    val request = schema.findEntity("Request").get
    val sourceIp = request.attribute("sourceIp").get.typeExpr
    assertEquals(sourceIp, SchemaType.Extension(ExtensionType.ipaddr))
  }

  test("Extension types: parses decimal attribute") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Product = {
          "price": decimal,
        };
      }
    """)

    val product = schema.findEntity("Product").get
    val price = product.attribute("price").get.typeExpr
    assertEquals(price, SchemaType.Extension(ExtensionType.decimal))
  }

  test("Extension types: parses datetime attribute") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Event = {
          "startTime": datetime,
          "endTime"?: datetime,
        };
      }
    """)

    val event = schema.findEntity("Event").get
    val startTime = event.attribute("startTime").get.typeExpr
    assertEquals(startTime, SchemaType.Extension(ExtensionType.datetime))

    val endTime = event.attribute("endTime").get
    assertEquals(endTime.optional, true)
    assertEquals(endTime.typeExpr, SchemaType.Extension(ExtensionType.datetime))
  }

  test("Extension types: parses duration attribute") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity Session = {
          "timeout": duration,
          "maxIdle"?: duration,
        };
      }
    """)

    val session = schema.findEntity("Session").get
    val timeout = session.attribute("timeout").get.typeExpr
    assertEquals(timeout, SchemaType.Extension(ExtensionType.duration))

    val maxIdle = session.attribute("maxIdle").get
    assertEquals(maxIdle.optional, true)
    assertEquals(maxIdle.typeExpr, SchemaType.Extension(ExtensionType.duration))
  }

  test("Extension types: parses Set of extension types") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity NetworkPolicy = {
          "allowedIps": Set<ipaddr>,
        };
      }
    """)

    val policy = schema.findEntity("NetworkPolicy").get
    val allowedIps = policy.attribute("allowedIps").get.typeExpr
    assertEquals(allowedIps, SchemaType.SetOf(SchemaType.Extension(ExtensionType.ipaddr)))
  }

  test("Extension types: parses all extension types in one entity") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity AuditLog = {
          "clientIp": ipaddr,
          "cost": decimal,
          "timestamp": datetime,
          "responseTime": duration,
        };
      }
    """)

    val log = schema.findEntity("AuditLog").get
    assertEquals(log.attribute("clientIp").get.typeExpr, SchemaType.Extension(ExtensionType.ipaddr))
    assertEquals(log.attribute("cost").get.typeExpr, SchemaType.Extension(ExtensionType.decimal))
    assertEquals(log.attribute("timestamp").get.typeExpr, SchemaType.Extension(ExtensionType.datetime))
    assertEquals(log.attribute("responseTime").get.typeExpr, SchemaType.Extension(ExtensionType.duration))
  }

  test("Extension types: parses extension types in action context") {
    val schema = CedarSchema.parseUnsafe("""
      namespace Test {
        entity User;
        entity Resource;
        
        action "access" appliesTo {
          principal: [User],
          resource: [Resource],
          context: {
            "requestTime": datetime,
            "sourceIp": ipaddr,
            "sessionDuration"?: duration,
          },
        };
      }
    """)

    val action = schema.findAction("access").get
    val context = action.contextType.get
    assertEquals(context.get("requestTime").get.typeExpr, SchemaType.Extension(ExtensionType.datetime))
    assertEquals(context.get("sourceIp").get.typeExpr, SchemaType.Extension(ExtensionType.ipaddr))
    assertEquals(context.get("sessionDuration").get.typeExpr, SchemaType.Extension(ExtensionType.duration))
    assertEquals(context.get("sessionDuration").get.optional, true)
  }

  // ===========================================================================
  // Error Handling
  // ===========================================================================

  test("Error handling: reports parse error with location") {
    val result = CedarSchema.parse("""
      namespace Test {
        entity User =
      }
    """)

    assert(result.isLeft)
    val error = result.left.toOption.get
    assert(error.message.nonEmpty)
  }

  test("Error handling: parseUnsafe throws on error") {
    intercept[IllegalArgumentException] {
      CedarSchema.parseUnsafe("not valid cedar schema")
    }
  }

  // ===========================================================================
  // Comments
  // ===========================================================================

  test("Comment handling: ignores single-line comments") {
    val schema = CedarSchema.parseUnsafe("""
      // This is a comment
      namespace Test {
        // Another comment
        entity User; // Inline comment
      }
    """)

    assertEquals(schema.allEntities.length, 1)
  }

  // ===========================================================================
  // Real-World Schema Test
  // ===========================================================================

  test("Real-world schema: parses sample.cedarschema") {
    val stream = getClass.getResourceAsStream("/sample.cedarschema")
    val content = scala.io.Source.fromInputStream(stream).mkString

    val schema = CedarSchema.parseUnsafe(content)

    // Basic structure checks
    assertEquals(schema.namespaces.length, 1)
    assertEquals(schema.namespaces.head.name.get.value, "SampleApp")

    // Entity checks
    val entityNames = schema.allEntities.map(_.name).toSet
    assert(entityNames.contains("Tenant"))
    assert(entityNames.contains("Role"))
    assert(entityNames.contains("User"))
    assert(entityNames.contains("TenantMembership"))
    assert(entityNames.contains("Project"))
    assert(entityNames.contains("Task"))
    assert(entityNames.contains("Document"))

    // Action checks
    val actionNames = schema.allActions.map(_.name).toSet
    assert(actionNames.contains("Project::create"))
    assert(actionNames.contains("Project::read"))
    assert(actionNames.contains("Project::update"))
    assert(actionNames.contains("Project::delete"))

    // Hierarchy checks
    val hierarchy = schema.entityHierarchy
    assert(hierarchy.roots.contains("Tenant"))
    assert(hierarchy.roots.contains("Role"))
    assert(hierarchy.roots.contains("User"))
    assert(hierarchy.parentsOf("Task").contains("Project"))
    assert(hierarchy.parentsOf("Task").contains("Tenant"))
    assert(hierarchy.maxDepth >= 2)
  }
}
