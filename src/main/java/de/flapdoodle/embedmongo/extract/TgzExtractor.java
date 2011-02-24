/**
 * Copyright (C) 2011 Michael Mosmann <michael@mosmann.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.flapdoodle.embedmongo.extract;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import de.flapdoodle.embedmongo.Files;

public class TgzExtractor implements IExtractor {

	@Override
	public void extract(File source, File destination, Pattern file) throws IOException {

		FileInputStream fin = new FileInputStream(source);
		BufferedInputStream in = new BufferedInputStream(fin);
		GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in);

		TarArchiveInputStream tarIn = new TarArchiveInputStream(gzIn);
		try {
			TarArchiveEntry entry;
			while ((entry = tarIn.getNextTarEntry()) != null) {
				if (file.matcher(entry.getName()).matches()) {
					System.out.println("File: " + entry.getName());
					if (tarIn.canReadEntryData(entry)) {
						System.out.println("Can Read: " + entry.getName());
						long size = entry.getSize();
						Files.write(tarIn, size, destination);
						destination.setExecutable(true);
						System.out.println("DONE");
					}
					break;
					
				}
				else {
					System.out.println("SKIP File: " + entry.getName());
				}
			}

		} finally {
			tarIn.close();
			gzIn.close();
		}
	}
}