/*
 * Copyright 2009-2012 Tilmann Z�schke. All rights reserved.
 * 
 * This file is part of ZooDB.
 * 
 * ZooDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ZooDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ZooDB.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * See the README and COPYING files for further information. 
 */
package org.zoodb.jdo.internal.server;


import org.zoodb.jdo.internal.SerialInput;

/**
 * This class serves as a mediator between the serializer and the file access class.
 * 
 * @author Tilmann Z�schke
 */
public class ObjectReader implements SerialInput {

	private final StorageChannelInput in;
	
	private long byteReadCounter = 0;
	
	public void resetByteReadCounter() {
		this.byteReadCounter = 0;
	}
	
	public long getByteReadCounter() {
		return byteReadCounter;
	}
	
	public ObjectReader(StorageChannel file) {
		this.in = file.getReader(true);
	}

    @Override
    public int readInt() {
    	byteReadCounter += 4;
        return in.readInt();
    }

    @Override
    public long readLong() {
    	byteReadCounter += 8;
        return in.readLong();
    }

    @Override
    public boolean readBoolean() {
    	byteReadCounter += 1;
        return in.readBoolean();
    }

    @Override
    public byte readByte() {
    	byteReadCounter += 1;
        return in.readByte();
    }

    @Override
    public char readChar() {
    	byteReadCounter += 2;
        return in.readChar();
    }

    @Override
    public double readDouble() {
    	byteReadCounter += 8;
        return in.readDouble();
    }

    @Override
    public float readFloat() {
    	byteReadCounter += 4;
        return in.readFloat();
    }

    @Override
    public short readShort() {
    	byteReadCounter += 2;
        return in.readShort();
    }

    @Override
    public void readFully(byte[] array) {
    	byteReadCounter += array.length;
    	in.readFully(array);
    }

    @Override
    public String readString() {
    	String s = in.readString(); 
    	byteReadCounter += 2*s.length();
        return s;
    }

    @Override
    public void skipRead(int nBytes) {
    	in.skipRead(nBytes);
    }

    @Override
    public void seekPosAP(long pageAndOffs) {
        in.seekPosAP(pageAndOffs);
    }

    @Override
    public void seekPage(int page, int offs) {
        in.seekPage(page, offs);
    }

    public long startReading(int page, int offs) {
        in.seekPage(page, offs);
        return in.readLongAtOffset(0);
    }
    
//    @Override
//    public String toString() {
//    	return "pos=" + file.getPage() + "/" + file.getOffset();
//    }
}
