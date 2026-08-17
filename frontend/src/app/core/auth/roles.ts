// TS mirror of backend/src/main/kotlin/com/sec/security/Roles.kt's Role object. Kept in step by
// hand — nothing checks the two files against each other, so a rename on one side and not the
// other is a silent 403 rather than a compile error.
export const Role = {
  USER: 'sec-user',
  ADMIN: 'sec-admin',
  ACCESS_MANAGER: 'sec-access-manager',
  AUDITOR: 'sec-auditor',
} as const;
