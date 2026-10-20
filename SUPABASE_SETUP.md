# Supabase setup

The app reads its Supabase project URL and publishable/anon key from the ignored
`local.properties` file. Add these lines to that file (keep any existing
`sdk.dir` line):

```properties
supabase.url=https://YOUR_PROJECT_REF.supabase.co
supabase.anonKey=YOUR_SUPABASE_PUBLISHABLE_OR_ANON_KEY
```

Use the project URL and the publishable/anon key from the Supabase project API
settings. Never put a `service_role` or secret key in the Android app.

Email/password sign-in and sign-up are implemented. If email confirmation is
enabled for the project, new users will be asked to confirm their email before
signing in. The SQL in `supabase/schema.sql` is a separate database setup step;
run it in the Supabase SQL editor if you want to create the TaskSync tables and
policies. The current app still stores task data locally in Room.
