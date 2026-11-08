# Tables & Relationships
To be determined. Here are some early thoughts:
- Gift
  - name: string
  - price: double?
  - links: [string]?
- Event
  - name: string
  - group: UUID (foreign key)
  - owner: UUID (foreign key)
  - end_date: date?
- User
  - username: string (unique)
  - display_name: string?
- GroupUser
  - group_id: UUID (foreign key)
  - user_id: UUID (foreign key)

Unsure of additional schema.
