/*
 * ThunderNetwork - Server Client Architecture to send Off-Chain Bitcoin Payments
 * Copyright (C) 2015 Mats Jerratsch <matsjj@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package network.thunder.core.communication.layer.high.payments;

import network.thunder.core.etc.Tools;

import java.util.Arrays;

public class PaymentSecret {

    public byte[] secret;
    public byte[] hash;

    public PaymentSecret (byte[] secret, byte[] hash) {
        this.secret = secret;
        this.hash = hash;
    }

    public PaymentSecret (byte[] secret) {
        this.secret = secret;
        this.hash = Tools.hashSecret(secret);
    }

    public boolean verify () {
        if (this.hash == null || this.secret == null) {
            return false;
        }
        return Arrays.equals(this.hash, Tools.hashSecret(secret));
    }

    @Override
    public boolean equals (Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        PaymentSecret that = (PaymentSecret) o;

        return Arrays.equals(hash, that.hash);

    }

    @Override
    public String toString () {
        return "PaymentSecret{" +
                "hash=" + Tools.bytesToHex(hash).substring(0, 10) +
                '}';
    }

    @Override
    public int hashCode () {
        return Arrays.hashCode(hash);
    }
}
