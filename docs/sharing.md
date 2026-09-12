# Sharing

Two people, one car, and a backend that cannot read where it is.

## The two paths

**Manual share** is an ordinary Android share sheet carrying a maps link. It works with anyone, needs
no account on either side, survives being pasted into a message, and opens on an iPhone. This is what
you want when you are texting a friend, and it is the only path most users will ever need.

**Household share** is the automatic one: paired devices, silent delivery, and encryption the server
cannot undo. It is off by default and requires an explicit pairing step.

## Why encrypt

Auto-sharing means a server would otherwise accumulate a timestamped record of where a couple parks
their car, indefinitely. That dataset reveals where they live, where they work, and where they were
on any given evening. There is no product reason for anyone but the two of them to read it, and
"we promise not to look" is not a design.

So the server holds `{from, at, envelope}` — two user ids, a timestamp, and an opaque blob.

## The scheme

One AES-256-GCM key per household, generated on the device that creates it.

**Key transfer is a QR code.** One person holds their phone up to the other's camera. That is an air
gap: the key never touches the network, there is no key agreement protocol to get wrong, and there is
no server-side copy to subpoena or leak. The QR payload uses a `curbside://` scheme rather than an
`https://` link precisely so it is *not* followable — a link invites being pasted into a browser or a
chat, and this is a secret.

Each event is sealed under a fresh random 96-bit nonce. GCM authenticates as well as encrypts, so a
tampered or truncated record fails to open rather than decoding to plausible coordinates.

**At rest**, the household key is wrapped: a hardware-backed AES key in Android Keystore, which never
leaves the secure element, encrypts it, and the wrapped blob sits in ordinary preferences. The key
itself has to be exportable — otherwise pairing a second device would be impossible — so a
keystore-only design is not available. Wrapping means extracting it needs both a copy of the file and
that device's secure hardware.

The wrapping key is deliberately *not* auth-gated: the parking notification has to be readable from
the lock screen while you are walking to the car.

## Delivery

Firestore does three jobs: durable storage, real-time fan-out via snapshot listener, and an offline
write queue. The last one is what makes garage sharing work — the write succeeds immediately with no
signal and syncs later, with no retry logic of ours.

The FCM push carries **only** `{eventId, fromName}`. No coordinates, because the server has none. The
receiving device fetches the encrypted document and opens it locally. That keeps the end-to-end
property intact through the notification path, which is usually where such schemes quietly leak.

A `ShareWorker` backstops the cases Firestore's own queue cannot cover — signed out, or no household
yet at the moment of parking — and the weekly maintenance worker re-enqueues anything still pending.

## Pairing flow

1. Phone A: *Show pairing code*. Mints a household, a 256-bit key, and an invite code valid for 15
   minutes. Renders `curbside://pair?h=…&c=…&k=…` as a QR.
2. Phone B scans it. Imports the key, verifies the invite exists, matches, and has not expired, then
   adds itself to the member list and **deletes the invite**. Single use: a photographed QR code
   should not be a standing invitation to somebody's parking history.
3. Both phones can now read each other's envelopes.

The invite code alphabet omits I, O, 0 and 1, because the code may have to be read aloud.

## Firestore rules

In `firestore.rules`. They exist to stop the failure modes encryption does not cover:

- Households are readable only by members, so nobody enumerates who is in one.
- A household can be created only with its creator as sole member.
- A non-member may add **themselves and nobody else** — the exact operation the invite flow needs,
  and nothing more.
- A parking record must be attributed to the writer, so one partner cannot forge a spot in the
  other's name.
- Invites are gettable but not listable: the code is the secret, and it only ever travels inside a QR
  that also carries the key.

## What is deliberately not protected

- **The social graph and timing.** The server sees who shares with whom and when a car was parked,
  because it has to route the push. Hiding traffic patterns is a much larger project.
- **Recovery.** Losing both phones loses the history. There is no server-side key, so there is
  nothing to recover with. That is the correct trade for this data.
- **A malicious partner.** Anyone you pair with can read everything you share with them. That is what
  sharing means.

## User controls

- Auto-share is off until switched on.
- Any single event can be suppressed, and `ShareWorker` re-checks the flag before publishing in case
  it was set after the event was queued.
- *Leave household* destroys the local key, which makes every stored envelope permanently opaque on
  that device.
- Backup rules exclude the wrapped key: a restored copy would be undecryptable anyway, since the
  hardware key stayed on the old phone, and pairing again takes one scan.
