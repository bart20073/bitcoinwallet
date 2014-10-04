/**
 * Copyright 2011 Will Harris will@phase.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <stdio.h>
#include <stdlib.h>
#include <malloc.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <memory.h>
#include "sha2.h"
#include "rmd160.h"
#include "bigdigits.h"

#define KEY_DATA_LENGTH 0x41 // 65 bytes length of public key
#define NAME_DATA_LENGTH 0x22 // public key string
#define VERSION_PROD 0 // bitcoin version
#define KEYSTRING_LENGTH ( NAME_DATA_LENGTH + 1 ) // include null

static const char* pszBase58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

struct keyHit
{
	char keyString[KEYSTRING_LENGTH];
	int hitCount;
};

static struct keyHit** g_pHits = NULL;
static int g_numKeys = 0;

void printKeys( int threshold )
{
	int i = 0;
	for ( i = 0; i < g_numKeys; i++ )
	{
		if ( g_pHits[i]->hitCount >= threshold )
		{
			printf("%s\n", g_pHits[i]->keyString );
		}
	}
}

void addHit( const char* keyString )
{
	int success = 0;
	int i = 0;
	int found = 0;
	void* reallocTemp = NULL;

	for ( i = 0; i < g_numKeys; i++ )
	{
		if ( 0 == strcmp( g_pHits[i]->keyString, keyString ) )
		{
			g_pHits[i]->hitCount++;
			found = 1;
			break;
		}
	}

	if ( !found )
	{
		struct keyHit* pHit = ( struct keyHit* ) malloc( sizeof ( struct keyHit ) );

		if ( NULL != pHit )
		{
			memset( pHit, 0, sizeof( struct keyHit* ) );
			pHit->hitCount = 1;
			strncpy( pHit->keyString, keyString, KEYSTRING_LENGTH );

			reallocTemp = realloc( g_pHits, ( ( g_numKeys + 1 ) * sizeof ( struct keyHit* ) ) );

			if ( NULL != reallocTemp )
			{
				g_pHits = (struct keyHit**) reallocTemp;
				g_pHits[g_numKeys++] = pHit;
				success = 1;
			}

			if ( !success )
			{
				free ( pHit );
			}
		}
	}
}

void hexdump( const unsigned char *buffer, size_t length )
{
	size_t i = 0;

	for ( i= 0; i < length; i++)
	{
		printf("%02x", buffer[i]);
	}
	printf("\n");
}

#define DIGITS 20

void base58Decode( const unsigned char* buffer ) // input is 25 bytes long
{
	DIGIT_T num[DIGITS];
	DIGIT_T next_num[DIGITS];
	DIGIT_T r = 0;
	char outbuffer[40];
	size_t offset = 39;

	memset( outbuffer, 0, 40 );
	(void) mpConvFromOctets(num, DIGITS, buffer, 25 );

	do
	{
		r =  mpShortDiv( next_num, num, 58, DIGITS );
		outbuffer[--offset] = pszBase58[ r ];
		mpSetEqual( num, next_num, DIGITS );
	}
	while ( ! mpIsZero( num, DIGITS ) );

	// leading 0
	outbuffer[--offset] = pszBase58[0];

	addHit( outbuffer + offset );
}

// I heard you liked SHA-256 so I put a SHA-256 inside your SHA-256...
unsigned char* getChecksum( const unsigned char* keyHash ) // 21 bytes input
{
	bool success = false;
	static unsigned char output [4];
	int length = 0;
	sha2* sha_1 = new sha2();
	if ( NULL != sha_1 )
	{
		sha_1->Init( sha_1->enuSHA256 );
		sha_1->Update( keyHash, 21 );
		sha_1->End();
		// second sha
		sha2* sha_2 = new sha2();

		if ( NULL != sha_2 )
		{
			sha_2->Init( sha_2->enuSHA256 );
			sha_2->Update( (const sha_byte*) sha_1->RawHash( length ), 32 );
			sha_2->End();
			memcpy( output, sha_2->RawHash( length ), 4 );
			success = true;
			delete sha_2;
		}

		delete sha_1;
	}

	if ( success )
		return output;
	else
		return NULL;
}

void decodeAddress( const unsigned char* key )
{
	unsigned char* pShaBuffer = NULL;
	unsigned char* pRMDBuffer = NULL;
	int length = 0;
	unsigned char fullkey[1 + 20 + 4]; // version + sha256(md160(key)) + checksum where checksum is sha256(sha256(version . sha256(md160(key)) )
	unsigned char* checksum;

	sha2* sha = new sha2();
	if ( NULL != sha )
	{
		sha->Init( sha->enuSHA256 );
		sha->Update( key, KEY_DATA_LENGTH );
		sha->End();
		pShaBuffer = (unsigned char*) sha->RawHash( length );

		pRMDBuffer = RMD160( pShaBuffer, 32 );

		fullkey[0] = VERSION_PROD;
		memcpy( fullkey + 1, pRMDBuffer, 20 );
		checksum = getChecksum( fullkey );

		if ( NULL != checksum )
		{
			memcpy( fullkey + 1 + 20, checksum, 4 );
			base58Decode( fullkey );
		}

		delete sha;
	}
}

void decodeWallet( unsigned char* pBuffer, off_t size )
{
	unsigned char keySig [] = { 2 + ( sizeof("key") - 1 ) + KEY_DATA_LENGTH, 0x00, 0x01, sizeof("key") - 1, 'k', 'e', 'y', KEY_DATA_LENGTH };
	unsigned char nameSig [] = { 2 + ( sizeof("name") - 1 ) + NAME_DATA_LENGTH, 0x00, 0x01, sizeof("name") - 1, 'n', 'a', 'm', 'e', NAME_DATA_LENGTH };
	unsigned char nameSig2 [] = { 2 + ( sizeof("name") - 1 ) + ( NAME_DATA_LENGTH - 1 ), 0x00, 0x01, sizeof("name") - 1, 'n', 'a', 'm', 'e', ( NAME_DATA_LENGTH - 1 ) };

	unsigned char key[KEY_DATA_LENGTH];
	char name[NAME_DATA_LENGTH + 1];

	size_t offset = 0;

	// first try and find the public keys
	for ( offset = 0 ; offset < size - ( KEY_DATA_LENGTH + sizeof( keySig ) ); offset++ )
	{
		if ( 0 == memcmp( pBuffer + offset, keySig, sizeof( keySig ) ) )
		{
			memcpy( key, pBuffer + offset + sizeof( keySig ), KEY_DATA_LENGTH );
			decodeAddress( key );
		}
	}
	/** turns out we can't use these keys - we have to export ALL the public keys to have a reliable balance... TODO: optimise the android app to cope with this
	// then find stored addresses (22 char long)
	for ( offset = 0 ; offset < size - ( NAME_DATA_LENGTH + sizeof( nameSig ) ); offset++ )
	{
		if ( 0 == memcmp( pBuffer + offset, nameSig, sizeof( nameSig ) ) )
		{
			memset( name, 0, NAME_DATA_LENGTH + 1 );
			memcpy( name, pBuffer + offset + sizeof( nameSig), NAME_DATA_LENGTH );
			addHit( name );
		}
	}

	// then other stored addresses (21 char long)
	for ( offset = 0 ; offset < size - ( NAME_DATA_LENGTH + sizeof( nameSig2 ) ); offset++ )
	{
		if ( 0 == memcmp( pBuffer + offset, nameSig2, sizeof( nameSig2 ) ) )
		{
			memset( name, 0, NAME_DATA_LENGTH + 1 );
			memcpy( name, pBuffer + offset + sizeof( nameSig2), NAME_DATA_LENGTH -1 );
			addHit( name );
		}
	}
	*/
	//anything with a score >= 2 is probably a key we want
	printKeys( 1 );
}

int main( int argc, char** argv )
{
	if ( argc == 2 )
	{
		unsigned char* pBuffer = NULL;
		struct stat stat_t;
		size_t bytesRead = 0;
		FILE* file = NULL;
		off_t size = 0;
		
		if ( 0 == stat( argv[1], &stat_t ) )
		{
			size = stat_t.st_size;

			pBuffer = (unsigned char*) malloc( size );

			if ( NULL != pBuffer )
			{
				file = fopen( argv[1], "rb" );

				if ( file != NULL )
				{
					bytesRead = fread( pBuffer, sizeof(char), size, file );

					if ( bytesRead == size )
					{
						decodeWallet( pBuffer, size );
					}
					else
					{
						fprintf( stderr, "Error: incorrect number of bytes read\n" );
					}

					fclose( file );
				}
				else
				{
					fprintf( stderr, "Error: cannot open file %s for read\n", argv[1] );
				}

				free( pBuffer );
			}
			else
			{
				fprintf( stderr, "Error: out of memory\n" );
			}
		}
		else
		{
			fprintf( stderr, "Error: cannot stat file %s\n", argv[1] );
		}
	}
	else
	{
		fprintf( stderr, "Usage %s <path to wallet.dat>\n", argv[0] );
	}

	return 0;
}
