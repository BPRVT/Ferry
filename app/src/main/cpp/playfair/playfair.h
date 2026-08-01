/*
 * FairPlay key decryption — public interface.
 *
 * Origin:  EstebanKubata/playfair <https://github.com/EstebanKubata/playfair> (GPLv3)
 * Via:     FD-/RPiPlay lib/playfair -> FDH2/UxPlay -> mazer666/PhairPlay -> Ferry
 *
 * This file carried no license header upstream. Neither RPiPlay's copy nor
 * PhairPlay's copy had one, so the omission was inherited rather than introduced
 * by either project. This header records the license that in fact governs the
 * file: both EstebanKubata/playfair and RPiPlay are licensed GPLv3, and RPiPlay's
 * README credits lib/playfair to EstebanKubata's PlayFair under the GNU GPL.
 *
 * See NOTICE and AUDIT.md for the full provenance chain and a byte-level diff
 * against upstream.
 *
 * Copyright (C) EstebanKubata and the RPiPlay contributors
 * Copyright (C) 2026 Ferry contributors
 *
 * This file is part of Ferry.
 *
 * Ferry is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef PLAYFAIR_H
#define PLAYFAIR_H

void playfair_decrypt(unsigned char* message3, unsigned char* cipherText, unsigned char* keyOut);

#endif
