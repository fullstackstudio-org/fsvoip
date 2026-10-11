# Anonymous calling

**Status: not in build 6.** The "Bellen als anoniem" choice (shown in the reference screens) needs a proof on the house PBX first (plan `fsvoip-app-v2`, Task 0):
does the provider (Maxitel) honour a `Privacy: id` / `P-Preferred-Identity` request, and which number does the callee see? Until that is
proven, `capabilities.anonymousCalls` does not exist in the server answer and the app shows no such choice. The engine already knows
`CallOptions.anonymous` (it adds the `Privacy` header only for an anonymous call; there is a test), so the UI part is small once the answer is in.

When Task 0 is done, write the outcome here: the header(s) that work, the provider behaviour, and any limits.
