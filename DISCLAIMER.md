# Legal Disclaimers

## General Disclaimer

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED. IN NO EVENT SHALL THE AUTHORS, COPYRIGHT HOLDERS, OR CONTRIBUTORS
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES ARISING FROM THE USE OF THIS SOFTWARE.

## OFAC/Sanctions Screening Disclaimer

TranzFer MFT includes an OFAC (Office of Foreign Assets Control) and AML
(Anti-Money Laundering) screening capability that checks transfers against
the U.S. Treasury SDN (Specially Designated Nationals) list.

**IMPORTANT LIMITATIONS:**

1. The screening feature is provided as a supplementary tool only and does
   NOT constitute a complete compliance program.

2. The SDN list data is sourced from publicly available U.S. Treasury
   publications. While we update regularly, there may be delays between
   official list updates and software updates.

3. Fuzzy matching algorithms may produce false positives or false negatives.
   All matches should be reviewed by qualified compliance personnel.

4. This software does NOT replace the need for a qualified compliance
   officer, legal counsel, or a comprehensive sanctions compliance program.

5. Users are solely responsible for their own compliance with OFAC
   regulations, sanctions laws, and anti-money laundering requirements
   in their jurisdiction.

6. The developers and maintainers of TranzFer MFT accept NO LIABILITY
   for any fines, penalties, or legal consequences arising from reliance
   on this screening feature.

7. For authoritative sanctions data, always refer to the official
   U.S. Treasury OFAC website: https://sanctionssearch.ofac.treas.gov/

## PCI DSS Compliance Disclaimer

TranzFer MFT implements security controls aligned with PCI DSS requirements,
including HMAC-based audit trails, encryption at rest and in transit, and
access controls.

**IMPORTANT:**

1. Use of this software alone does NOT make your organization PCI DSS
   compliant. PCI DSS compliance requires a holistic program covering
   people, processes, and technology.

2. Organizations must conduct their own PCI DSS assessment with a
   Qualified Security Assessor (QSA) or complete a Self-Assessment
   Questionnaire (SAQ) as appropriate.

3. The software provides tools to SUPPORT compliance, not guarantee it.

## HIPAA Disclaimer

TranzFer MFT can be configured to support HIPAA (Health Insurance
Portability and Accountability Act) requirements for protected health
information (PHI) transmission.

**IMPORTANT:**

1. Use of this software does NOT automatically make your organization
   HIPAA compliant.

2. A Business Associate Agreement (BAA) between relevant parties is
   required when handling PHI.

3. Organizations must implement their own HIPAA compliance program
   including administrative, physical, and technical safeguards.

4. The software supports encryption, access controls, and audit logging
   that can be part of a HIPAA compliance strategy.

## GDPR Disclaimer

For users in the European Union or handling EU personal data:

1. This software can be configured to support GDPR requirements, but
   use of this software alone does NOT ensure GDPR compliance.

2. Data controllers remain responsible for their own GDPR obligations
   including data processing agreements, privacy impact assessments,
   and data subject rights management.

3. See PRIVACY.md for our data handling practices.

## EDI/Financial Data Disclaimer

The EDI conversion, translation, and processing features are provided for
data transformation purposes only.

1. Users must validate all EDI output against their trading partner
   requirements before transmission.

2. The developers accept no liability for financial losses, transaction
   failures, or compliance violations resulting from EDI processing errors.

3. Always test EDI translations in a non-production environment before
   deploying to production workflows.

## AI/Machine Learning Disclaimer

TranzFer MFT includes AI-powered features for file classification,
anomaly detection, and automated remediation.

1. AI predictions and classifications are probabilistic and may contain
   errors. Critical decisions should include human review.

2. The AI engine does not transmit data to external services. All
   processing occurs within your deployment environment.

3. AI model accuracy depends on the quality and volume of training data
   in your specific environment.

## Limitation of Liability

TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT SHALL
THE AUTHORS, COPYRIGHT HOLDERS, CONTRIBUTORS, OR AFFILIATES BE LIABLE
FOR ANY SPECIAL, INCIDENTAL, INDIRECT, OR CONSEQUENTIAL DAMAGES
WHATSOEVER (INCLUDING, WITHOUT LIMITATION, DAMAGES FOR LOSS OF BUSINESS
PROFITS, BUSINESS INTERRUPTION, LOSS OF BUSINESS INFORMATION, LOSS OF
DATA, OR ANY OTHER PECUNIARY LOSS) ARISING OUT OF THE USE OF OR
INABILITY TO USE THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
SUCH DAMAGES.
