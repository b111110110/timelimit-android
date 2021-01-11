/*
 * TimeLimit Copyright <C> 2019 - 2020 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.flavor

import io.timelimit.android.BuildConfig
import okhttp3.CertificatePinner

object CertificatePinning {
    val configuration = CertificatePinner.Builder()
            .add(
                    BuildConfig.serverDomain,
                    // echo -n "sha256/"; curl -s https://letsencrypt.org/certs/lets-encrypt-r3.pem | openssl x509 -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
                    "sha256/jQJTbIh0grw0/1TkHSumWb+Fs0Ggogr621gT3PvPKG0=",
                    // echo -n "sha256/"; curl -s https://letsencrypt.org/certs/lets-encrypt-e1.pem | openssl x509 -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
                    "sha256/J2/oqMTsdhFWW/n85tys6b4yDBtb6idZayIEBx7QTxA=",
                    // echo -n "sha256/"; curl -s https://letsencrypt.org/certs/lets-encrypt-r4.pem | openssl x509 -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
                    "sha256/5VReIRNHJBiRxVSgOTTN6bdJZkpZ0m1hX+WPd5kPLQM=",
                    // echo -n "sha256/"; curl -s https://letsencrypt.org/certs/lets-encrypt-e2.pem | openssl x509 -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
                    "sha256/vZNucrIS7293MQLGt304+UKXMi78JTlrwyeUIuDIknA="
            )
            // This is theoretically not required because the fallback happens at
            // the DNS query level => original domain is assumed for certificate verification.
            // However, in case this is changed in the future, then there is already
            // a host pinning for the other domain.
            .add(
                    BuildConfig.backupServerDomain,
                    // echo -n "sha256/"; curl -s https://letsencrypt.org/certs/lets-encrypt-r3.pem | openssl x509 -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
                    "sha256/jQJTbIh0grw0/1TkHSumWb+Fs0Ggogr621gT3PvPKG0=",
                    // echo -n "sha256/"; curl -s https://letsencrypt.org/certs/lets-encrypt-e1.pem | openssl x509 -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
                    "sha256/J2/oqMTsdhFWW/n85tys6b4yDBtb6idZayIEBx7QTxA=",
                    // echo -n "sha256/"; curl -s https://letsencrypt.org/certs/lets-encrypt-r4.pem | openssl x509 -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
                    "sha256/5VReIRNHJBiRxVSgOTTN6bdJZkpZ0m1hX+WPd5kPLQM=",
                    // echo -n "sha256/"; curl -s https://letsencrypt.org/certs/lets-encrypt-e2.pem | openssl x509 -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
                    "sha256/vZNucrIS7293MQLGt304+UKXMi78JTlrwyeUIuDIknA="
            )
            .build()
}
