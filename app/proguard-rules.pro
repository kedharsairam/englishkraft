# EnglishKraft — R8 / ProGuard rules
#
# Only rules with a reason. A keep rule that has no explanation is a rule nobody
# will ever remove.

# --- the dictionary -----------------------------------------------------------
# Reflection is not used to reach any dictionary class, so no keep rule is needed
# for them. That is deliberate: the repository is opened through
# SQLiteDatabase.openDatabase and every query is hand-written SQL, so R8 can see
# the whole surface. Adding keeps here would hide a design change.

# --- Compose ------------------------------------------------------------------
# Compose ships its own consumer rules; adding more here is unnecessary.

# --- crash reporting ----------------------------------------------------------
# Deliberately absent. The app ships no analytics and no crash reporting, so
# there is nothing to keep alive. If that ever changes, the SDK's own rules
# apply and this file says so rather than carrying stale entries.

# --- notes --------------------------------------------------------------------
# Keep line numbers so a stack trace from a release build is still readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile