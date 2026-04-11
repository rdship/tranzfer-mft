# VIP Forms — Treating users as gods

> "The UI should feel like a personal assistant who is there just in case you need them."

This is the design spec every form in the TranzFer MFT admin UI follows. If you're adding a new page with a form, read this first.

## The core idea

Traditional form UIs treat users as suspects. Red asterisks, red borders, red error messages, modal confirm dialogs for every action. The implicit message is "you probably did something wrong, let me correct you."

**We flip that.** Forms in this app treat users as VIPs — the platform's whole job is to make their work effortless, guide them gently when something's missing, and never shout. Top-tier product companies (Netflix, Stripe, Linear, Airbnb, Notion) have figured this out. We copy the good parts.

## The 10 rules

### 1. Required ≠ red asterisk

- **Don't:** `<label>Email Address *</label>` with a red `*`
- **Do:** Small muted "required" pill next to the label. Rounded, uppercase, subtle. It reads as informational, not as an accusation.

```jsx
<FormField label="Email" required name="email" helper="...">
  <input ... />
</FormField>
```

The `<FormField>` component renders the pill automatically when `required` is set.

### 2. Helper text is always visible

Every non-trivial field gets a `helper` prop explaining **why** the field exists, not just that it's required. The helper text is visible all the time — when the field is empty, when it's filled, even when there's an error (the error takes visual priority).

- **Don't:** Helper text that appears only on focus or disappears when the user clicks away.
- **Do:** Calm, permanent helper text in muted color that reads like a conversation partner.

Good helpers:
- ✅ "How you'll recognize this partner across reports"
- ✅ "We'll send login credentials here"
- ❌ "This field is required"  (it already says that)
- ❌ "Must be unique across your tenant"  (rule, not user value)

### 3. Complex fields get an InfoHint

For fields where a one-line helper isn't enough, add a `tooltip` prop. The `<FormField>` renders a small `?` icon next to the label that reveals a longer paragraph on hover/click.

```jsx
<FormField
  label="SFTP server"
  required
  name="serverInstance"
  helper="Which SFTP server this account connects through"
  tooltip="Each server instance has its own host, port, and TLS config. Pick sftp-server-1 for the primary cluster, sftp-server-2 for failover."
>
  <select>...</select>
</FormField>
```

### 4. Errors are amber, not red

Validation errors render in a warm amber tone (`rgb(245, 158, 11)`) with a soft left-border stripe. Red is reserved for things that are actually broken — a backend outage, a service crash, an auth failure.

When a user misses a required field, that's not a crime. It's a gentle nudge. The color reflects that.

### 5. Error messages are actions, not rules

- **Don't:** "Name is required."
- **Do:** "Add a name to continue."
- **Don't:** "Invalid email."
- **Do:** "Double-check this — looks like the email is missing a piece."
- **Don't:** "Password must be at least 8 characters."
- **Do:** "Make the password at least 8 characters so it holds up."

Every error message starts with what the user **can do**, not what they **did wrong**.

### 6. Live success cues

As the user types in a required field and the field becomes valid, a small green check appears next to the label. Users see their progress in real time. No need to submit or tab away to find out.

This is free — just pass `valid={!errors.name && !!form.name}` to `<FormField>`.

### 7. Submit-fail is a guided scroll, not a popup

When the user clicks Save and a required field is missing:

1. `useGentleValidation` computes the errors
2. The page smooth-scrolls to the first missing field
3. The input auto-focuses (cursor blinking, ready to type)
4. A single calm amber toast appears: "Just one more thing — a name is needed" or "3 more details to save this partner"
5. The field itself shows its amber left-border + error message

**Zero modal alerts. Zero red walls. Zero blame.**

```jsx
const { errors, handleSubmit } = useGentleValidation({
  rules: [
    { field: 'name',  label: 'Partner name', required: true },
    { field: 'email', label: 'Email',        required: true, validate: validators.email },
  ],
  onValid: (form) => mutation.mutate(form),
  recordKind: 'partner',
})

<form onSubmit={handleSubmit(form)}>
  <FormField label="Partner name" required name="name" error={errors.name} valid={!errors.name && !!form.name} helper="...">
    <input value={form.name} onChange={e => { setForm({...form, name: e.target.value}); clearFieldError('name') }} />
  </FormField>
  ...
</form>
```

### 8. Errors clear the moment the user addresses them

Don't wait for the next submit. The moment the user types a character in a previously-failing field, its error disappears. This is free — call `clearFieldError(fieldName)` inside the field's `onChange`.

### 9. Multi-step forms use `FormWizard`

Any form with more than ~6 fields should be broken into steps. Netflix onboarding, Stripe checkout, and Airbnb booking all do this. The `<FormWizard>` primitive renders a stepper, per-step validation, back/next navigation, and a final-step Save button.

```jsx
<FormWizard
  steps={[
    { id: 'identity',  title: 'Who are you onboarding?', fields: [...], rules: [...], render: ({ form, setForm, errors }) => <>...</> },
    { id: 'protocols', title: 'How will you exchange files?', ... },
    { id: 'review',    title: 'Review and confirm', ... },
  ]}
  form={form}
  setForm={setForm}
  onComplete={(finalForm) => mutation.mutate(finalForm)}
  onCancel={() => navigate(-1)}
  submitLabel="Create partner"
  loading={mutation.isPending}
  recordKind="partner"
/>
```

The wizard:
- Validates per-step (clicking Next only checks the current step's rules)
- Shows a stepper at the top with green-check completed steps
- Allows back-navigation to any completed step
- Replaces "Next" with "Create partner" on the final step
- Uses the same calm amber validation tone as single forms

### 10. Keyboard is first-class

Users should be able to fill out a form without touching the mouse. `useEnterAdvances` makes pressing Enter advance to the next field (like Netflix, Stripe, Linear), and the final field's Enter submits.

```jsx
const onKeyDown = useEnterAdvances({ onSubmit: () => handleSubmit(form)(null) })

<form onKeyDown={onKeyDown} onSubmit={handleSubmit(form)}>
  ...
</form>
```

Textareas are excluded — Enter in a textarea is still a newline.

### 11. Sample values beat empty fields

An empty input with a label is intimidating. "Company Name: [__________]" makes the user pause and think "what format am I supposed to use?" Top-tier products eliminate that hesitation by showing **clickable sample values** right below the field.

The `samples` prop on `<FormField>` renders a row of clickable pills that prefill the input when clicked. Users get an instant answer to "what should I put here?" without having to read documentation.

```jsx
<FormField
  label="Company Name"
  required
  name="companyName"
  helper="How you'll recognize this partner"
  samples={['ACME Trading Co', 'Global Logistics Inc', 'Pacific Supply Ltd']}
  onSampleClick={(val) => setForm({ ...form, companyName: val })}
>
  <input ... />
</FormField>
```

Rules for good sample values:
- **3-4 is the sweet spot.** More than 4 clutters the field.
- **Realistic, not cute.** Use the kind of value a real user would enter, not placeholder filler like "Foo Inc" or "Acme".
- **Varied format.** Show different shapes (short/long, with/without suffix) so users see the range of accepted input.
- **Never reveal real customer data.** The samples must be invented or obviously generic.

### 12. Time-of-day-aware modal backdrops

When a modal opens, the backdrop overlay shouldn't be a single hardcoded `rgba(0,0,0,0.72)`. Top-tier apps (macOS Dynamic Desktop, iOS Auto Dark Mode, Notion Dark Mode) subtly adjust visuals based on ambient context. We do the same for modal backdrops:

- **Morning (08-12):** cool blue overlay, slightly lighter — matches alert-and-productive mode
- **Afternoon (12-17):** neutral warm grey
- **Evening (17-20):** amber sunset tint, slightly darker
- **Night (20-00):** deep indigo, darker — eyes are tired, we fade the background more
- **Late night (00-05):** near-black with blue undertone, darkest — respects the operator working at 2 AM
- **Dawn (05-08):** soft warm peach, lightest

The `useTimeOfDayBackdrop` hook returns a ready-to-spread style object. Every modal backdrop uses it. The overlay color transitions smoothly over 800 ms if the user leaves the tab open across a time boundary.

```jsx
const backdrop = useTimeOfDayBackdrop()

<div
  className="fixed inset-0 z-50 flex items-center justify-center"
  style={backdrop.style}
  onClick={onClose}
  data-tod={backdrop.label}
>
  <div className="modal-card">...</div>
</div>
```

### 13. Never dummy — 360° integrated

**No feature in this app is ever a mock.** Every button, every panel, every count, every chart is wired to a real backend endpoint. No "coming soon" stubs, no hardcoded sample responses, no `if (env === 'dev') returnFakeData()`.

This is a hard rule, not a guideline. The moment you ship a fake panel to "make the page look full," you've broken the user's trust. If a feature isn't ready for real data, hide it entirely from the sidebar and put it on the plan doc instead.

The only exceptions:
- **Keyboard shortcut cheatsheet** can label planned-but-not-yet-wired shortcuts as `(planned)` in muted text, so users know what's coming.
- **Demo data seeder** (`scripts/demo-traffic.sh`) intentionally inserts synthetic rows into a dev environment. This is explicitly labeled and only runs against a fresh demo database.

Before merging any new page, grep it for these anti-patterns:
```bash
grep -n "MOCK\|fakeData\|dummyData\|TODO: wire\|coming soon\|// FAKE\|// STUB" newPage.jsx
```
If any match, either wire the missing piece to a real endpoint or remove the panel.

## Destructive actions: prefer undo over confirm

For **routine** destructive actions (archive a flow, dismiss a notification, clear a filter, soft-delete a record), use `useUndoToast` instead of `<ConfirmDialog>`. Let the action happen optimistically and show a 7-second "Undo" toast.

Reserve `<ConfirmDialog>` for actions that are **genuinely irreversible**:
- Deleting a partner with N child accounts
- Terminating a running execution
- Wiping a volume
- Deleting a user
- Revoking a certificate

Everything else gets undo.

```jsx
const { showUndoToast } = useUndoToast()

const handleArchive = (flow) => {
  archiveFlow.mutate(flow.id)  // fires immediately
  showUndoToast({
    message: `Archived "${flow.name}"`,
    onUndo: () => unarchiveFlow.mutate(flow.id),
  })
}
```

## The full primitive set

| Primitive | What it does | When to use |
|---|---|---|
| `<FormField>` | Calm required pill, helper text, amber errors, live success check, InfoHint tooltip | Every form field, everywhere |
| `useGentleValidation` | Submit-fail smooth scroll + focus + friendly toast | Every form with validation |
| `<FormWizard>` | Multi-step form with stepper, per-step validation | Forms with > 6 fields |
| `useEnterAdvances` | Enter advances focus, last Enter submits | Every non-trivial form |
| `<ConfirmDialog>` | Modal confirmation with danger/warning/info/success variants | Truly irreversible actions |
| `useUndoToast` | Optimistic action + 7s undo toast | Routine reversible actions |
| `<CopyButton>` | One-click clipboard with toast + checkmark | Any displayed ID, hash, key |
| `<ServiceUnavailable>` | Standard backend-down card with retry | Any page with a backend dependency |

## Microcopy guidelines

Every sentence in every form, error, toast, tooltip, and empty state follows these rules:

- **Start with the verb the user should do.** "Add a name" not "A name is needed."
- **Use "you" and "your."** The user is the subject, not a hypothetical third party.
- **Prefer contractions.** "It's" not "It is." Sounds human.
- **Never use "please."** It implies the app is begging. The app is confident and in service.
- **Never use exclamation marks.** Calm confidence over excitement.
- **Never say "please try again."** Say what to try.
- **Use "we" sparingly.** The app is an assistant, not a marketing team. "We'll" is fine for forward-looking ("We'll send credentials to this email"); "We apologize" is not.

## Reviewing a new form

Before merging a new form page, answer yes to all of these:

- [ ] Every `<label>` uses the `<FormField>` primitive (no raw labels)
- [ ] Every required field has a `helper` explaining WHY
- [ ] Complex fields have a `tooltip` explaining the technical detail
- [ ] Submit uses `useGentleValidation` (no `alert()`, no `window.confirm()`)
- [ ] Errors use the amber error message pattern, not red
- [ ] Error messages start with a verb the user can do
- [ ] No required field displays a red asterisk
- [ ] Forms with > 6 fields use `<FormWizard>`
- [ ] Keyboard flow works (Tab, Enter advances, Cmd+Enter submits)
- [ ] Destructive actions use `useUndoToast` (not `<ConfirmDialog>`) unless genuinely irreversible

---

**Last rule: if a form makes you feel like a robot reading requirements, rewrite it. If it makes you feel like someone is quietly handing you exactly what you need, keep it.**
