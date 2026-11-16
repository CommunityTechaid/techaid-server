# Equalities Sheet: service account runbook

The public request form's native equalities form (dashboard flag `native-equalities-form`) sends
its answers to `submitEqualities`. The server appends **one row per submission** to the isolated
equalities Google Sheet and stores none of the answers: no database column, no log line (see
`GraphQlTelemetryConfig`, which redacts this operation). The code is in
`cta.app.graphql.mutations.EqualitiesMutations` and `cta.app.services.GoogleSheetsEqualitiesWriter`.

## How access works

- One Google **service account per environment**: `equalities-uat` and `equalities-prod`, in the
  existing Community TechAid GCP project, with the **Google Sheets API** enabled.
- **No project roles.** A service account can only reach a spreadsheet that has been shared with
  its email address, so the UAT account can never write to the production Sheet.
- Production writes to a tab called `TaDa responses` in the existing equalities spreadsheet
  (alongside the Typeform tab). UAT writes to a separate test spreadsheet.

## Settings (Container App env vars / secrets)

| Variable | Value |
|---|---|
| `EQUALITIES_SHEET_ID` | The spreadsheet ID: the part of its URL between `/d/` and `/edit`. |
| `EQUALITIES_SHEET_TAB` | Tab name. Defaults to `TaDa responses`. |
| `EQUALITIES_SA_KEY` | **Secret.** The service account's JSON key, raw or base64-encoded. |

If any is missing, every submit fails with `EQUALITIES_SHEET_UNAVAILABLE` and the request is not
completed, so keep the flag off until all three are set.

## Setting it up (once per environment)

1. Google Cloud console → the existing project → APIs & Services → enable **Google Sheets API**.
2. IAM & Admin → Service Accounts → create `equalities-uat` (or `equalities-prod`). Grant **no**
   roles. Keys → Add key → JSON. Treat the downloaded file as a secret and delete it once stored.
3. Open the spreadsheet → add the tab (`TaDa responses`) → paste this header row into row 1:

   ```
   Submitted at | Correlation ID | Borough | Ward | What type of organisation are you? | What is your client's age? | Which of the following best describes your client's gender? | Which of the following best describes your client's sexual orientation? | Does your client receive state benefits? | Is your client No Recourse to Public Funds? | Does your client consider themselves to have a disability or long term health condition? | What is your client's race or ethnic group? | What is your client's housing status? | Is your client in education? | Is your client working?
   ```

   (One cell per item. The authoritative list is `EqualitiesQuestions.SHEET_HEADER`.)
4. Share the spreadsheet with the service account's email (`…@….iam.gserviceaccount.com`) as
   **Editor**. Untick "Notify people".
5. Store the key and the IDs on the Container App:

   ```bash
   az containerapp secret set -n <app> -g <rg> --secrets equalities-sa-key="$(base64 -w0 key.json)"
   az containerapp update -n <app> -g <rg> --set-env-vars \
     EQUALITIES_SA_KEY=secretref:equalities-sa-key \
     EQUALITIES_SHEET_ID=<spreadsheet id> \
     EQUALITIES_SHEET_TAB="TaDa responses"
   ```

6. Turn on `native-equalities-form` on the Feature Flags admin page, submit a test request, and
   check that a row appears.

## The row

`Submitted at` (London time), `Correlation ID`, `Borough`, `Ward`, then one column per question in
form order. A question the form skipped (No Recourse to Public Funds, when benefits = Yes) is an
empty cell. Values are written RAW, so nothing is ever read as a formula.

There is **no request ID**, as with Typeform: TaDa clears the correlation ID once the step
completes, so a row cannot be joined back to a request or a person from TaDa's side.

A rare duplicate is possible: if the row was appended but completing the request then failed, the
referee's retry appends a second row with the same correlation ID. Filter on that column.

## Rotating or revoking

- **Rotate:** add a new JSON key to the service account, update the `equalities-sa-key` secret,
  restart the revision, then delete the old key.
- **Revoke at once:** remove the service account from the spreadsheet's sharing. Submits then fail
  with `EQUALITIES_SHEET_UNAVAILABLE`, so switch the flag off at the same time (the form falls back
  to Typeform while that path still exists).
