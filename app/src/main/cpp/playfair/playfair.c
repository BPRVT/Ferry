/*
 * FairPlay key decryption — top-level entry point (playfair_decrypt).
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

#include <stdint.h>

#include "playfair.h"

void generate_key_schedule(unsigned char* key_material, uint32_t key_schedule[11][4]);
void generate_session_key(unsigned char* oldSap, unsigned char* messageIn, unsigned char* sessionKey);
void cycle(unsigned char* block, uint32_t key_schedule[11][4]);
void z_xor(unsigned char* in, unsigned char* out, int blocks);
void x_xor(unsigned char* in, unsigned char* out, int blocks);

extern unsigned char default_sap[];

void playfair_decrypt(unsigned char* message3, unsigned char* cipherText, unsigned char* keyOut)
{
	unsigned char* chunk1 = &cipherText[16];
	unsigned char* chunk2 = &cipherText[56];
	int i;
	unsigned char blockIn[16];
	unsigned char sapKey[16];
	uint32_t key_schedule[11][4];
	generate_session_key(default_sap, message3, sapKey);	
	generate_key_schedule(sapKey, key_schedule);
	z_xor(chunk2, blockIn, 1);
	cycle(blockIn, key_schedule);
	for (i = 0; i < 16; i++) {
		keyOut[i] = blockIn[i] ^ chunk1[i];
	}
	x_xor(keyOut, keyOut, 1);
	z_xor(keyOut, keyOut, 1);
}

